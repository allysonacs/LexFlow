package com.lexflow.api.admin;

/**
 * Corpo da criação e da atualização de uma regra de checklist.
 *
 * @param caseType tipo de demanda; obrigatório na criação e ignorado na atualização — o tipo de uma
 *     regra nunca muda
 * @param requiredDocumentType código do documento exigido, em {@code UPPER_SNAKE_CASE}
 * @param mandatory obrigatório; {@code true} quando a falta do documento impede a suficiência
 */
public record ChecklistRuleRequest(
        String caseType, String requiredDocumentType, String description, Boolean mandatory) {}
