package com.lexflow.application.review;

import com.lexflow.domain.decision.Decision;
import com.lexflow.domain.legalcase.LegalCase;
import java.util.Objects;

/**
 * Resultado do registro de uma decisão.
 *
 * @param repeated {@code true} quando a requisição repetiu uma chave de idempotência já usada e nada
 *     novo foi criado
 */
public record RegisterDecisionResult(Decision decision, LegalCase legalCase, boolean repeated) {

    public RegisterDecisionResult {
        Objects.requireNonNull(decision, "decision não pode ser nulo");
        Objects.requireNonNull(legalCase, "legalCase não pode ser nulo");
    }
}
