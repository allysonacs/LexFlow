package com.lexflow.domain.decision;

import com.lexflow.domain.legalcase.LegalCaseStatus;

/** Decisão final do responsável humano sobre uma demanda (seção 1 da base de conhecimento). */
public enum DecisionType {

    APPROVED(LegalCaseStatus.APPROVED),
    REJECTED(LegalCaseStatus.REJECTED),
    RETURNED_FOR_CORRECTION(LegalCaseStatus.RETURNED_FOR_CORRECTION);

    private final LegalCaseStatus resultingStatus;

    DecisionType(LegalCaseStatus resultingStatus) {
        this.resultingStatus = resultingStatus;
    }

    /** Status para o qual a demanda vai quando esta decisão é registrada. */
    public LegalCaseStatus resultingStatus() {
        return resultingStatus;
    }
}
