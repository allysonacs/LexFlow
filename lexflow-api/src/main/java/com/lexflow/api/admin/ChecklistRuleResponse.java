package com.lexflow.api.admin;

import com.lexflow.domain.checklist.ChecklistRule;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.util.UUID;

/** Uma regra de checklist, como a API administrativa a apresenta. */
public record ChecklistRuleResponse(
        UUID id, LegalCaseType caseType, String requiredDocumentType, String description, boolean mandatory) {

    public static ChecklistRuleResponse from(ChecklistRule rule) {
        return new ChecklistRuleResponse(
                rule.id(), rule.caseType(), rule.requiredDocumentType(), rule.description(), rule.mandatory());
    }
}
