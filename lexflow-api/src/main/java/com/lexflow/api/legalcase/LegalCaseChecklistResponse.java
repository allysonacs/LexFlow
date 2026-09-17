package com.lexflow.api.legalcase;

import com.lexflow.application.checklist.LegalCaseChecklist;
import com.lexflow.domain.checklist.ChecklistItemStatus;
import com.lexflow.domain.checklist.ChecklistRule;
import com.lexflow.domain.checklist.DocumentChecklist;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resposta do {@code GET /api/v1/legal-cases/{id}/checklist}.
 *
 * @param evaluated {@code false} enquanto a demanda não foi classificada e o checklist não existe
 * @param hasSufficientDocumentation resposta determinística a {@code HAS_SUFFICIENT_DOCUMENTATION}
 * @param missingMandatoryDocumentTypes documentos obrigatórios que ainda faltam
 */
public record LegalCaseChecklistResponse(
        UUID legalCaseId,
        LegalCaseType caseType,
        LegalCaseStatus status,
        boolean evaluated,
        boolean hasSufficientDocumentation,
        List<String> missingMandatoryDocumentTypes,
        List<Item> items) {

    /** Um item do checklist, com os dados da regra que o originou. */
    public record Item(
            UUID id,
            UUID checklistRuleId,
            String requiredDocumentType,
            String description,
            boolean mandatory,
            ChecklistItemStatus status,
            UUID documentId,
            Instant evaluatedAt) {}

    public static LegalCaseChecklistResponse from(LegalCaseChecklist view) {
        DocumentChecklist checklist = view.checklist();
        return new LegalCaseChecklistResponse(
                view.legalCase().id(),
                view.legalCase().caseType(),
                view.legalCase().status(),
                view.evaluated(),
                view.hasSufficientDocumentation(),
                checklist.missingMandatoryDocumentTypes(),
                checklist.items().stream()
                        .map(item -> {
                            ChecklistRule rule = checklist.ruleOf(item);
                            return new Item(
                                    item.id(),
                                    rule.id(),
                                    rule.requiredDocumentType(),
                                    rule.description(),
                                    rule.mandatory(),
                                    item.status(),
                                    item.documentId(),
                                    item.evaluatedAt());
                        })
                        .toList());
    }
}
