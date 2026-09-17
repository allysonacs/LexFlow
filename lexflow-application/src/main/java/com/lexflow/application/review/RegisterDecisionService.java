package com.lexflow.application.review;

import com.lexflow.application.exception.IdempotentRequestInProgressException;
import com.lexflow.application.idempotency.IdempotencyNamespace;
import com.lexflow.application.idempotency.IdempotentOperation;
import com.lexflow.application.idempotency.IdempotentOperationStore;
import com.lexflow.application.legalcase.LegalCaseRepository;
import com.lexflow.application.legalcase.LegalCaseStatusHistoryRepository;
import com.lexflow.application.legalcase.LegalCaseStatusTransitionResult;
import com.lexflow.application.legalcase.LegalCaseStatusTransitionService;
import com.lexflow.application.transaction.TransactionRunner;
import com.lexflow.domain.decision.Decision;
import com.lexflow.domain.exception.LegalCaseNotFoundException;
import com.lexflow.domain.legalcase.LegalCase;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Registra a decisão de um responsável humano e leva a demanda ao status correspondente (Prompt 15).
 *
 * <p><strong>Este caso de uso não decide nada.</strong> Ele grava a decisão de uma pessoa
 * identificada e aplica a consequência dela na máquina de estados. A IA não aparece aqui.
 *
 * <p><strong>Decisão e transição na mesma transação.</strong> Uma decisão gravada sem a transição
 * correspondente deixaria a demanda parada em revisão para sempre; uma transição sem a decisão
 * apagaria quem decidiu. As duas coisas são uma só operação.
 *
 * <p><strong>Idempotência.</strong> Com {@code Idempotency-Key}, reenviar a mesma decisão devolve a
 * decisão original, sem criar uma segunda linha nem uma segunda transição — que, aliás, a máquina de
 * estados recusaria, já que a demanda não está mais em {@code PENDING_HUMAN_REVIEW}.
 */
public class RegisterDecisionService {

    private final LegalCaseRepository legalCaseRepository;
    private final DecisionRepository decisionRepository;
    private final LegalCaseStatusTransitionService statusTransitionService;
    private final LegalCaseStatusHistoryRepository statusHistoryRepository;
    private final IdempotentOperationStore idempotencyStore;
    private final DecisionRegisteredEventPublisher eventPublisher;
    private final TransactionRunner transactionRunner;
    private final Clock clock;
    private final Supplier<UUID> idGenerator;

    public RegisterDecisionService(
            LegalCaseRepository legalCaseRepository,
            DecisionRepository decisionRepository,
            LegalCaseStatusTransitionService statusTransitionService,
            LegalCaseStatusHistoryRepository statusHistoryRepository,
            IdempotentOperationStore idempotencyStore,
            DecisionRegisteredEventPublisher eventPublisher,
            TransactionRunner transactionRunner,
            Clock clock,
            Supplier<UUID> idGenerator) {
        this.legalCaseRepository = Objects.requireNonNull(legalCaseRepository, "legalCaseRepository não pode ser nulo");
        this.decisionRepository = Objects.requireNonNull(decisionRepository, "decisionRepository não pode ser nulo");
        this.statusTransitionService =
                Objects.requireNonNull(statusTransitionService, "statusTransitionService não pode ser nulo");
        this.statusHistoryRepository =
                Objects.requireNonNull(statusHistoryRepository, "statusHistoryRepository não pode ser nulo");
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore não pode ser nulo");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher não pode ser nulo");
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner não pode ser nulo");
        this.clock = Objects.requireNonNull(clock, "clock não pode ser nulo");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator não pode ser nulo");
    }

    /**
     * Registra a decisão.
     *
     * @throws LegalCaseNotFoundException se a demanda não existir
     * @throws com.lexflow.domain.exception.InvalidStatusTransitionException se a demanda não estiver
     *     aguardando revisão humana
     * @throws IdempotentRequestInProgressException se a mesma chave estiver sendo usada por uma
     *     requisição que ainda não terminou
     */
    public RegisterDecisionResult register(RegisterDecisionCommand command) {
        Objects.requireNonNull(command, "command não pode ser nulo");
        LegalCase legalCase = legalCaseRepository
                .findById(command.legalCaseId())
                .orElseThrow(() -> new LegalCaseNotFoundException(command.legalCaseId()));

        if (command.hasIdempotencyKey()) {
            Optional<IdempotentOperation> existing = idempotencyStore.reserve(
                    IdempotencyNamespace.LEGAL_CASE_DECISION, command.idempotencyKey(), command.legalCaseId());
            if (existing.isPresent()) {
                return replay(existing.get());
            }
        }

        Instant decidedAt = clock.instant();
        Decision decision = new Decision(
                idGenerator.get(),
                legalCase.id(),
                command.decisionType(),
                command.decidedBy(),
                decidedAt,
                command.comments());

        // A transição é validada antes de qualquer gravação: uma demanda fora da revisão humana é
        // recusada aqui, e não depois de a decisão já estar no banco.
        LegalCaseStatusTransitionResult transition = statusTransitionService.transition(
                legalCase, command.decisionType().resultingStatus(), command.decidedBy(), reasonOf(command));

        transactionRunner.runInTransaction(() -> {
            decisionRepository.save(decision);
            legalCaseRepository.save(transition.legalCase());
            statusHistoryRepository.save(transition.historyEntry());
            if (command.hasIdempotencyKey()) {
                idempotencyStore.markCompleted(IdempotencyNamespace.LEGAL_CASE_DECISION, command.idempotencyKey());
            }
        });

        eventPublisher.publish(DecisionRegisteredEvent.of(
                idGenerator.get(),
                decision.id(),
                legalCase.id(),
                legalCase.caseType(),
                command.decisionType(),
                command.decidedBy(),
                clock.instant()));

        return new RegisterDecisionResult(decision, transition.legalCase(), false);
    }

    /** Decisões já registradas em uma demanda, da mais antiga para a mais recente. */
    public List<Decision> findByLegalCaseId(UUID legalCaseId) {
        return decisionRepository.findByLegalCaseId(legalCaseId);
    }

    /** Devolve a decisão registrada pela primeira requisição que usou esta chave. */
    private RegisterDecisionResult replay(IdempotentOperation operation) {
        if (!operation.completed()) {
            throw new IdempotentRequestInProgressException(operation.idempotencyKey());
        }
        LegalCase legalCase = legalCaseRepository
                .findById(operation.aggregateId())
                .orElseThrow(() -> new LegalCaseNotFoundException(operation.aggregateId()));
        Decision decision = decisionRepository.findByLegalCaseId(operation.aggregateId()).stream()
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalStateException(
                        "chave de idempotência concluída sem decisão gravada: " + operation.idempotencyKey()));
        return new RegisterDecisionResult(decision, legalCase, true);
    }

    /** Motivo gravado no histórico: quem decidiu e o que decidiu, sem o comentário livre. */
    private static String reasonOf(RegisterDecisionCommand command) {
        return "Decisão %s registrada por %s".formatted(command.decisionType(), command.decidedBy());
    }
}
