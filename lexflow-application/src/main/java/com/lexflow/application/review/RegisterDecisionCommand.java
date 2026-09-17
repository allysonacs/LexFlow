package com.lexflow.application.review;

import com.lexflow.domain.decision.DecisionType;
import java.util.Objects;
import java.util.UUID;

/**
 * Pedido de registro de uma decisão humana.
 *
 * @param decidedBy responsável identificado; enquanto não há provedor de identidade, vem do cabeçalho
 *     {@code X-User-Id} (Prompt 15)
 * @param idempotencyKey opcional; com ela, reenviar a mesma decisão não gera duas linhas nem duas
 *     transições
 */
public record RegisterDecisionCommand(
        UUID legalCaseId, DecisionType decisionType, String decidedBy, String comments, String idempotencyKey) {

    public RegisterDecisionCommand {
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        Objects.requireNonNull(decisionType, "decisionType não pode ser nulo");
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new IllegalArgumentException("decidedBy é obrigatório: toda decisão tem um responsável humano");
        }
        decidedBy = decidedBy.strip();
        comments = comments == null || comments.isBlank() ? null : comments.strip();
        idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.strip();
    }

    public boolean hasIdempotencyKey() {
        return idempotencyKey != null;
    }
}
