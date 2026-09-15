package com.lexflow.domain.checklist;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aplicação de uma {@link ChecklistRule} a uma demanda específica.
 *
 * <p>Imutável: cada avaliação devolve um novo item, preservando o anterior para fins de auditoria.
 */
public record DocumentChecklistItem(
        UUID id,
        UUID legalCaseId,
        UUID checklistRuleId,
        ChecklistItemStatus status,
        UUID documentId,
        Instant evaluatedAt) {

    public DocumentChecklistItem {
        Objects.requireNonNull(id, "id não pode ser nulo");
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        Objects.requireNonNull(checklistRuleId, "checklistRuleId não pode ser nulo");
        Objects.requireNonNull(status, "status não pode ser nulo");
        if (status == ChecklistItemStatus.SATISFIED && documentId == null) {
            throw new IllegalArgumentException("um item SATISFIED precisa referenciar o documento que o satisfez");
        }
    }

    /** Cria o item no estado inicial, ainda sem avaliação. */
    public static DocumentChecklistItem pending(UUID id, UUID legalCaseId, UUID checklistRuleId) {
        return new DocumentChecklistItem(id, legalCaseId, checklistRuleId, ChecklistItemStatus.PENDING, null, null);
    }

    /** Vincula o documento que atende à regra, marcando o item como satisfeito. */
    public DocumentChecklistItem satisfyWith(UUID documentId, Instant evaluatedAt) {
        Objects.requireNonNull(documentId, "documentId não pode ser nulo");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt não pode ser nulo");
        return new DocumentChecklistItem(
                id, legalCaseId, checklistRuleId, ChecklistItemStatus.SATISFIED, documentId, evaluatedAt);
    }

    /** Marca o item como pendente de documentação, desfazendo qualquer vínculo anterior. */
    public DocumentChecklistItem markMissing(Instant evaluatedAt) {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt não pode ser nulo");
        return new DocumentChecklistItem(
                id, legalCaseId, checklistRuleId, ChecklistItemStatus.MISSING, null, evaluatedAt);
    }

    public boolean isSatisfied() {
        return status == ChecklistItemStatus.SATISFIED;
    }
}
