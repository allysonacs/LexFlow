package com.lexflow.application.checklist;

/**
 * Campos editáveis de uma regra de checklist, usados na criação e na atualização.
 *
 * @param mandatory obrigatório; nulo é recusado para que a ausência do campo não vire "opcional" por
 *     engano
 */
public record ChecklistRuleCommand(String requiredDocumentType, String description, Boolean mandatory) {

    public ChecklistRuleCommand {
        if (mandatory == null) {
            throw new IllegalArgumentException("mandatory é obrigatório");
        }
    }
}
