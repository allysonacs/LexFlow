package com.lexflow.application.idempotency;

import java.util.Objects;
import java.util.UUID;

/**
 * Operação já associada a uma chave de idempotência.
 *
 * @param aggregateId agregado afetado pela operação original
 * @param completed {@code true} quando a operação chegou ao fim e o resultado pode ser devolvido como
 *     resposta repetida; {@code false} quando a chave está apenas reservada
 */
public record IdempotentOperation(
        IdempotencyNamespace namespace, String idempotencyKey, UUID aggregateId, boolean completed) {

    public IdempotentOperation {
        Objects.requireNonNull(namespace, "namespace não pode ser nulo");
        Objects.requireNonNull(aggregateId, "aggregateId não pode ser nulo");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey é obrigatória");
        }
    }
}
