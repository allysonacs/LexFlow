package com.lexflow.domain.decision;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Registro da decisão de um responsável humano sobre uma demanda.
 *
 * <p>A IA nunca cria uma decisão: {@code decidedBy} é sempre uma pessoa identificada (seção 1).
 */
public record Decision(
        UUID id, UUID legalCaseId, DecisionType decisionType, String decidedBy, Instant decidedAt, String comments) {

    public Decision {
        Objects.requireNonNull(id, "id não pode ser nulo");
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        Objects.requireNonNull(decisionType, "decisionType não pode ser nulo");
        Objects.requireNonNull(decidedAt, "decidedAt não pode ser nulo");
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new IllegalArgumentException("decidedBy é obrigatório: toda decisão tem um responsável humano");
        }
        if (decisionType == DecisionType.RETURNED_FOR_CORRECTION && (comments == null || comments.isBlank())) {
            throw new IllegalArgumentException("uma devolução para correção exige comentários explicando o motivo");
        }
    }
}
