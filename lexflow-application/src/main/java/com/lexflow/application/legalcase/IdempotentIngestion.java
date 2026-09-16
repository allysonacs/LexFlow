package com.lexflow.application.legalcase;

import java.util.Objects;
import java.util.UUID;

/**
 * Registro de uma ingestão já associada a uma chave de idempotência.
 *
 * @param completed {@code true} quando a ingestão chegou ao fim e a demanda pode ser devolvida como
 *     resposta repetida; {@code false} quando a chave está apenas reservada
 */
public record IdempotentIngestion(String idempotencyKey, UUID legalCaseId, boolean completed) {

    public IdempotentIngestion {
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey é obrigatória");
        }
    }
}
