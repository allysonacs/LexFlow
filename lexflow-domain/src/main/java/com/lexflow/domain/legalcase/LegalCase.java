package com.lexflow.domain.legalcase;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Agregado raiz do sistema: uma demanda jurídica que precisa de análise.
 *
 * <p>É imutável. Toda mudança de status devolve uma nova instância, o que garante que nenhum
 * colaborador consiga alterar o status "por baixo" das regras da máquina de estados.
 *
 * @param description texto livre do requisitante sobre a demanda; opcional. É um dos sinais usados
 *     pela classificação determinística do tipo (Prompt 08)
 */
public record LegalCase(
        UUID id,
        String externalReference,
        LegalCaseType caseType,
        LegalCaseStatus status,
        String requester,
        String description,
        CasePriority priority,
        Instant createdAt,
        Instant updatedAt) {

    /** Tamanho máximo da descrição, o bastante para um resumo sem virar um documento paralelo. */
    public static final int DESCRIPTION_MAX_LENGTH = 2000;

    /** Regras usadas quando nenhuma máquina de estados é informada explicitamente. */
    private static final LegalCaseStatusTransition DEFAULT_TRANSITION_RULES = new LegalCaseStatusTransitionRules();

    public LegalCase {
        Objects.requireNonNull(id, "id não pode ser nulo");
        Objects.requireNonNull(caseType, "caseType não pode ser nulo");
        Objects.requireNonNull(status, "status não pode ser nulo");
        Objects.requireNonNull(priority, "priority não pode ser nulo");
        Objects.requireNonNull(createdAt, "createdAt não pode ser nulo");
        Objects.requireNonNull(updatedAt, "updatedAt não pode ser nulo");
        if (requester == null || requester.isBlank()) {
            throw new IllegalArgumentException("requester é obrigatório");
        }
        if (externalReference != null && externalReference.isBlank()) {
            throw new IllegalArgumentException("externalReference, quando informado, não pode ser vazio");
        }
        if (description != null && description.isBlank()) {
            throw new IllegalArgumentException("description, quando informada, não pode ser vazia");
        }
        if (description != null && description.length() > DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "description não pode ter mais de %d caracteres".formatted(DESCRIPTION_MAX_LENGTH));
        }
    }

    /**
     * Cria uma demanda recém-recebida, já no status {@link LegalCaseStatus#RECEIVED}.
     *
     * @param externalReference identificador do caso no sistema de origem; pode ser nulo
     */
    public static LegalCase receive(
            UUID id,
            String externalReference,
            LegalCaseType caseType,
            String requester,
            CasePriority priority,
            Instant receivedAt) {
        return receive(id, externalReference, caseType, requester, null, priority, receivedAt);
    }

    /**
     * Mesma operação, com a descrição informada pelo requisitante.
     *
     * @param description pode ser nula
     */
    public static LegalCase receive(
            UUID id,
            String externalReference,
            LegalCaseType caseType,
            String requester,
            String description,
            CasePriority priority,
            Instant receivedAt) {
        return new LegalCase(
                id,
                externalReference,
                caseType,
                LegalCaseStatus.RECEIVED,
                requester,
                description,
                priority,
                receivedAt,
                receivedAt);
    }

    /**
     * Devolve uma nova demanda no status informado, usando as regras padrão da máquina de estados.
     *
     * @throws com.lexflow.domain.exception.InvalidStatusTransitionException se a transição não for permitida
     */
    public LegalCase transitionTo(LegalCaseStatus targetStatus, Instant occurredAt) {
        return transitionTo(targetStatus, occurredAt, DEFAULT_TRANSITION_RULES);
    }

    /**
     * Mesma operação, com a máquina de estados informada explicitamente — usada pelo serviço de
     * transição da camada de aplicação (Prompt 04) e pelos testes.
     */
    public LegalCase transitionTo(
            LegalCaseStatus targetStatus, Instant occurredAt, LegalCaseStatusTransition transitionRules) {
        Objects.requireNonNull(occurredAt, "occurredAt não pode ser nulo");
        Objects.requireNonNull(transitionRules, "transitionRules não pode ser nulo");
        transitionRules.validateTransition(status, targetStatus);
        return new LegalCase(
                id, externalReference, caseType, targetStatus, requester, description, priority, createdAt, occurredAt);
    }

    /** Indica se a demanda pode ir para o status informado, sem lançar exceção. */
    public boolean canTransitionTo(LegalCaseStatus targetStatus) {
        return DEFAULT_TRANSITION_RULES.isAllowed(status, targetStatus);
    }

    /** Status alcançáveis a partir do status atual. */
    public Set<LegalCaseStatus> allowedNextStatuses() {
        return DEFAULT_TRANSITION_RULES.allowedTransitionsFrom(status);
    }

    /** Indica se a demanda já chegou ao estado terminal. */
    public boolean isClosed() {
        return status.isTerminal();
    }
}
