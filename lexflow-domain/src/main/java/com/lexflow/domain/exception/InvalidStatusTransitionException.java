package com.lexflow.domain.exception;

import com.lexflow.domain.legalcase.LegalCaseStatus;

/**
 * Lançada quando se tenta mover uma demanda para um status que a máquina de estados não permite
 * (seção 4 da base de conhecimento).
 */
public class InvalidStatusTransitionException extends DomainException {

    private final LegalCaseStatus currentStatus;
    private final LegalCaseStatus targetStatus;

    public InvalidStatusTransitionException(LegalCaseStatus currentStatus, LegalCaseStatus targetStatus) {
        super("Transição de status inválida: %s -> %s".formatted(currentStatus, targetStatus));
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    public LegalCaseStatus currentStatus() {
        return currentStatus;
    }

    public LegalCaseStatus targetStatus() {
        return targetStatus;
    }
}
