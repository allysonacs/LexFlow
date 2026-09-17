package com.lexflow.application.checklist;

import com.lexflow.application.exception.ApplicationException;
import com.lexflow.domain.legalcase.LegalCaseType;

/** Lançada quando já existe regra para o mesmo documento no mesmo tipo de demanda. */
public class DuplicateChecklistRuleException extends ApplicationException {

    public DuplicateChecklistRuleException(LegalCaseType caseType, String requiredDocumentType) {
        super("Já existe regra de checklist para %s em %s".formatted(requiredDocumentType, caseType));
    }
}
