package com.lexflow.domain.checklist;

import com.lexflow.domain.legalcase.LegalCaseType;
import java.util.Objects;
import java.util.UUID;

/**
 * Regra que define qual documento é exigido para um tipo de demanda.
 *
 * <p>As regras são configuração de negócio e vivem em banco (seção 9), nunca fixas no código: este
 * record é só a representação em memória de uma linha de {@code checklist_rules}.
 */
public record ChecklistRule(
        UUID id, LegalCaseType caseType, String requiredDocumentType, String description, boolean mandatory) {

    public ChecklistRule {
        Objects.requireNonNull(id, "id não pode ser nulo");
        Objects.requireNonNull(caseType, "caseType não pode ser nulo");
        if (requiredDocumentType == null || requiredDocumentType.isBlank()) {
            throw new IllegalArgumentException("requiredDocumentType é obrigatório");
        }
    }
}
