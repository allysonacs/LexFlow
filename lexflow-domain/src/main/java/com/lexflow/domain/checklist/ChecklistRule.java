package com.lexflow.domain.checklist;

import com.lexflow.domain.document.Document;
import com.lexflow.domain.document.DocumentTypeCode;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.util.Objects;
import java.util.UUID;

/**
 * Regra que define qual documento é exigido para um tipo de demanda.
 *
 * <p>As regras são configuração de negócio e vivem em banco (seção 9), nunca fixas no código: este
 * record é só a representação em memória de uma linha de {@code checklist_rules}.
 *
 * @param requiredDocumentType código do documento exigido, normalizado por {@link DocumentTypeCode}
 * @param description explicação para quem envia a documentação; opcional
 * @param mandatory {@code true} quando a falta do documento impede a documentação de ser suficiente
 */
public record ChecklistRule(
        UUID id, LegalCaseType caseType, String requiredDocumentType, String description, boolean mandatory) {

    public ChecklistRule {
        Objects.requireNonNull(id, "id não pode ser nulo");
        Objects.requireNonNull(caseType, "caseType não pode ser nulo");
        if (requiredDocumentType == null || requiredDocumentType.isBlank()) {
            throw new IllegalArgumentException("requiredDocumentType é obrigatório");
        }
        requiredDocumentType = DocumentTypeCode.normalize(requiredDocumentType);
        description = description == null || description.isBlank() ? null : description.strip();
    }

    /** Devolve a mesma regra com os campos editáveis alterados; o tipo de demanda nunca muda. */
    public ChecklistRule withChanges(String newRequiredDocumentType, String newDescription, boolean newMandatory) {
        return new ChecklistRule(id, caseType, newRequiredDocumentType, newDescription, newMandatory);
    }

    /** Indica se o documento informado atende a esta regra. */
    public boolean isSatisfiedBy(Document document) {
        return document.isOfType(requiredDocumentType);
    }
}
