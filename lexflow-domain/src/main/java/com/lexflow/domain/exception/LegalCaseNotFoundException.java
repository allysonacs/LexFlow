package com.lexflow.domain.exception;

import java.util.UUID;

/** Lançada quando uma demanda jurídica referenciada não existe. */
public class LegalCaseNotFoundException extends DomainException {

    private final UUID legalCaseId;

    public LegalCaseNotFoundException(UUID legalCaseId) {
        super("Demanda jurídica não encontrada: %s".formatted(legalCaseId));
        this.legalCaseId = legalCaseId;
    }

    public UUID legalCaseId() {
        return legalCaseId;
    }
}
