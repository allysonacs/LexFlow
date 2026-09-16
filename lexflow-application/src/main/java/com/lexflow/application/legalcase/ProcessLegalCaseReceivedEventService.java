package com.lexflow.application.legalcase;

import com.lexflow.application.event.ProcessingEventStore;
import com.lexflow.application.event.ProcessingReservation;
import com.lexflow.application.transaction.TransactionRunner;
import com.lexflow.domain.exception.LegalCaseNotFoundException;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import java.util.Objects;

/**
 * Caso de uso disparado pelo consumo de {@link LegalCaseReceivedEvent}: tira a demanda da caixa de
 * entrada e a coloca em processamento.
 *
 * <p>Nesta etapa o trabalho é só avançar o status de {@link LegalCaseStatus#RECEIVED} para
 * {@link LegalCaseStatus#CLASSIFYING}. A classificação de verdade é do Prompt 08; o que já está no
 * lugar é a mecânica que ela vai usar — idempotência, transação e registro de histórico.
 *
 * <p><strong>A ordem das operações não é arbitrária.</strong> A reserva do evento vem antes de
 * qualquer escrita, para que uma entrega duplicada seja descartada sem efeito. A transição e a
 * marca de "processado" ficam na mesma transação, de modo que não existe estado em que a demanda
 * avançou mas o evento continua pendente — nem o contrário. E a marca de falha fica fora dela, já
 * que um rollback apagaria o próprio registro da falha e a mensagem voltaria a parecer virgem.
 */
public class ProcessLegalCaseReceivedEventService {

    /** Motivo gravado no histórico, para que a linha do tempo diga de onde veio a transição. */
    private static final String TRANSITION_REASON = "Processamento iniciado pelo consumo do evento "
            + LegalCaseReceivedEvent.EVENT_TYPE;

    private final LegalCaseRepository legalCaseRepository;
    private final LegalCaseStatusHistoryRepository statusHistoryRepository;
    private final LegalCaseStatusTransitionService statusTransitionService;
    private final ProcessingEventStore processingEventStore;
    private final TransactionRunner transactionRunner;

    public ProcessLegalCaseReceivedEventService(
            LegalCaseRepository legalCaseRepository,
            LegalCaseStatusHistoryRepository statusHistoryRepository,
            LegalCaseStatusTransitionService statusTransitionService,
            ProcessingEventStore processingEventStore,
            TransactionRunner transactionRunner) {
        this.legalCaseRepository = Objects.requireNonNull(legalCaseRepository, "legalCaseRepository não pode ser nulo");
        this.statusHistoryRepository =
                Objects.requireNonNull(statusHistoryRepository, "statusHistoryRepository não pode ser nulo");
        this.statusTransitionService =
                Objects.requireNonNull(statusTransitionService, "statusTransitionService não pode ser nulo");
        this.processingEventStore = Objects.requireNonNull(processingEventStore, "processingEventStore não pode ser nulo");
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner não pode ser nulo");
    }

    /**
     * Processa o evento uma única vez.
     *
     * @return o desfecho, para que o consumidor saiba se houve trabalho ou se a entrega foi descartada
     * @throws LegalCaseNotFoundException se o evento apontar para uma demanda inexistente
     * @throws com.lexflow.domain.exception.InvalidStatusTransitionException se a demanda não estiver
     *     mais em um estado do qual seja possível começar o processamento
     */
    public LegalCaseProcessingOutcome process(LegalCaseReceivedEvent event) {
        Objects.requireNonNull(event, "event não pode ser nulo");

        ProcessingReservation reservation = processingEventStore.reserve(
                event.idempotencyKey(), LegalCaseReceivedEvent.EVENT_TYPE, event.legalCaseId());

        switch (reservation) {
            case ALREADY_PROCESSED -> {
                return LegalCaseProcessingOutcome.SKIPPED_ALREADY_PROCESSED;
            }
            case IN_PROGRESS_ELSEWHERE -> {
                return LegalCaseProcessingOutcome.SKIPPED_IN_PROGRESS;
            }
            case RESERVED -> {
                // segue o fluxo
            }
        }

        try {
            transactionRunner.runInTransaction(() -> startProcessing(event));
            return LegalCaseProcessingOutcome.PROCESSED;
        } catch (RuntimeException e) {
            // Sem marcar como processado, a próxima entrega tenta de novo — que é o que o Prompt 07
            // pede. Esgotadas as tentativas, a mensagem vai para a dead-letter.
            processingEventStore.markFailed(event.idempotencyKey());
            throw e;
        }
    }

    private void startProcessing(LegalCaseReceivedEvent event) {
        LegalCase legalCase = legalCaseRepository
                .findById(event.legalCaseId())
                .orElseThrow(() -> new LegalCaseNotFoundException(event.legalCaseId()));

        LegalCaseStatusTransitionResult transition = statusTransitionService.transition(
                legalCase,
                LegalCaseStatus.CLASSIFYING,
                LegalCaseStatusHistoryEntry.SYSTEM_ACTOR,
                TRANSITION_REASON);

        legalCaseRepository.save(transition.legalCase());
        statusHistoryRepository.save(transition.historyEntry());
        processingEventStore.markProcessed(event.idempotencyKey());
    }
}
