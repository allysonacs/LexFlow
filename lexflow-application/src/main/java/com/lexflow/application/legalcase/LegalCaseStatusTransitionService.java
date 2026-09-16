package com.lexflow.application.legalcase;

import com.lexflow.domain.exception.InvalidStatusTransitionException;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseStatusTransition;
import com.lexflow.domain.legalcase.LegalCaseStatusTransitionRules;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Ponto único de mudança de status de uma demanda jurídica.
 *
 * <p><strong>Regra de arquitetura:</strong> nenhum outro componente do sistema — controller,
 * consumer de fila, job agendado ou repositório — pode alterar {@link LegalCaseStatus} diretamente.
 * Toda transição passa por este serviço, que valida a regra da seção 4 da base de conhecimento e
 * produz o registro de histórico correspondente. Chamar {@code LegalCase.transitionTo} por fora
 * daqui, ou gravar o status direto no banco, quebra essa garantia e a rastreabilidade exigida pela
 * auditoria.
 *
 * <p>O serviço é puro: não conhece fila, banco nem HTTP, e não persiste nada. Ele devolve um
 * {@link LegalCaseStatusTransitionResult} com a demanda atualizada e o registro de histórico, e
 * quem chama grava os dois — a demanda pelo seu repositório e o registro pela porta
 * {@link LegalCaseStatusHistoryRepository} —, de preferência na mesma transação.
 */
public class LegalCaseStatusTransitionService {

    private final LegalCaseStatusTransition transitionRules;
    private final Clock clock;
    private final Supplier<UUID> idGenerator;

    /** Configuração padrão: regras da base de conhecimento, relógio do sistema em UTC e ids aleatórios. */
    public LegalCaseStatusTransitionService() {
        this(new LegalCaseStatusTransitionRules(), Clock.systemUTC(), UUID::randomUUID);
    }

    /**
     * Configuração explícita, usada pelos testes e por quem precisar de um relógio fixo ou de outra
     * máquina de estados.
     */
    public LegalCaseStatusTransitionService(
            LegalCaseStatusTransition transitionRules, Clock clock, Supplier<UUID> idGenerator) {
        this.transitionRules = Objects.requireNonNull(transitionRules, "transitionRules não pode ser nulo");
        this.clock = Objects.requireNonNull(clock, "clock não pode ser nulo");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator não pode ser nulo");
    }

    /**
     * Move a demanda para o status desejado.
     *
     * @param changedBy autor da mudança; use {@link LegalCaseStatusHistoryEntry#SYSTEM_ACTOR} quando
     *     ela vier do pipeline e não de uma pessoa
     * @param reason motivo da mudança; opcional
     * @return a demanda no novo status e o registro de histórico a ser persistido
     * @throws InvalidStatusTransitionException se a transição não for permitida pela seção 4
     */
    public LegalCaseStatusTransitionResult transition(
            LegalCase legalCase, LegalCaseStatus targetStatus, String changedBy, String reason) {
        Objects.requireNonNull(legalCase, "legalCase não pode ser nulo");
        Objects.requireNonNull(targetStatus, "targetStatus não pode ser nulo");

        LegalCaseStatus previousStatus = legalCase.status();
        Instant changedAt = clock.instant();
        // A validação fica no domínio: o serviço só orquestra e registra.
        LegalCase updatedCase = legalCase.transitionTo(targetStatus, changedAt, transitionRules);

        return new LegalCaseStatusTransitionResult(
                updatedCase,
                new LegalCaseStatusHistoryEntry(
                        idGenerator.get(),
                        legalCase.id(),
                        previousStatus,
                        targetStatus,
                        changedAt,
                        changedBy,
                        reason));
    }

    /**
     * Abre o histórico de uma demanda recém-recebida.
     *
     * <p>A demanda já nasce em {@link LegalCaseStatus#RECEIVED}, então não há transição a validar:
     * o que este método faz é garantir que nem o estado inicial fique de fora da linha do tempo
     * (usado pela ingestão, no Prompt 05). O registro sai com {@code previousStatus} nulo.
     *
     * @throws IllegalArgumentException se a demanda não estiver no status inicial
     */
    public LegalCaseStatusTransitionResult registerInitialStatus(
            LegalCase legalCase, String changedBy, String reason) {
        Objects.requireNonNull(legalCase, "legalCase não pode ser nulo");
        if (legalCase.status() != LegalCaseStatus.RECEIVED) {
            throw new IllegalArgumentException(
                    "o registro inicial só vale para uma demanda em RECEIVED, mas ela está em "
                            + legalCase.status());
        }

        return new LegalCaseStatusTransitionResult(
                legalCase,
                new LegalCaseStatusHistoryEntry(
                        idGenerator.get(),
                        legalCase.id(),
                        null,
                        LegalCaseStatus.RECEIVED,
                        clock.instant(),
                        changedBy,
                        reason));
    }

    /** Indica se a transição é permitida, sem lançar exceção nem produzir registro. */
    public boolean canTransition(LegalCase legalCase, LegalCaseStatus targetStatus) {
        Objects.requireNonNull(legalCase, "legalCase não pode ser nulo");
        return transitionRules.isAllowed(legalCase.status(), targetStatus);
    }

    /** Status alcançáveis a partir do status atual da demanda. */
    public Set<LegalCaseStatus> allowedNextStatuses(LegalCase legalCase) {
        Objects.requireNonNull(legalCase, "legalCase não pode ser nulo");
        return transitionRules.allowedTransitionsFrom(legalCase.status());
    }
}
