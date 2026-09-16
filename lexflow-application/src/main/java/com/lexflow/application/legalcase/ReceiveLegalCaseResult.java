package com.lexflow.application.legalcase;

import java.util.Objects;

/**
 * Resultado da ingestão.
 *
 * @param replayed {@code true} quando a requisição repetiu uma {@code Idempotency-Key} já usada e o
 *     que voltou é a demanda criada originalmente — nada foi criado desta vez
 */
public record ReceiveLegalCaseResult(LegalCaseWithDocuments legalCase, boolean replayed) {

    public ReceiveLegalCaseResult {
        Objects.requireNonNull(legalCase, "legalCase não pode ser nulo");
    }
}
