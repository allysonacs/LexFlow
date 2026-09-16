package com.lexflow.application.legalcase;

import com.lexflow.domain.legalcase.LegalCase;
import java.util.Objects;

/**
 * Resultado de uma transição: a demanda já no novo status e o registro de histórico correspondente.
 *
 * <p>Os dois andam juntos de propósito. Quem chama o serviço recebe as duas coisas na mesma
 * operação e persiste ambas na mesma transação, o que impede que uma demanda mude de status sem
 * deixar rastro no histórico.
 */
public record LegalCaseStatusTransitionResult(LegalCase legalCase, LegalCaseStatusHistoryEntry historyEntry) {

    public LegalCaseStatusTransitionResult {
        Objects.requireNonNull(legalCase, "legalCase não pode ser nulo");
        Objects.requireNonNull(historyEntry, "historyEntry não pode ser nulo");
    }
}
