package com.lexflow.domain.exception;

import java.util.UUID;

/**
 * Lançada quando um item de checklist referencia uma {@code ChecklistRule} que não existe.
 */
public class ChecklistRuleNotFoundException extends DomainException {

    private final UUID checklistRuleId;

    public ChecklistRuleNotFoundException(UUID checklistRuleId) {
        super("Regra de checklist não encontrada: %s".formatted(checklistRuleId));
        this.checklistRuleId = checklistRuleId;
    }

    public UUID checklistRuleId() {
        return checklistRuleId;
    }
}
