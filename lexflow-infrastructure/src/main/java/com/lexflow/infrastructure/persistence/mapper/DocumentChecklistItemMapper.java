package com.lexflow.infrastructure.persistence.mapper;

import com.lexflow.domain.checklist.DocumentChecklistItem;
import com.lexflow.infrastructure.persistence.entity.DocumentChecklistItemEntity;

/** Converte entre {@link DocumentChecklistItem} e {@link DocumentChecklistItemEntity}. */
public final class DocumentChecklistItemMapper {

    private DocumentChecklistItemMapper() {
        // classe utilitária
    }

    public static DocumentChecklistItemEntity toEntity(DocumentChecklistItem item) {
        return new DocumentChecklistItemEntity(
                item.id(),
                item.legalCaseId(),
                item.checklistRuleId(),
                item.status(),
                item.documentId(),
                item.evaluatedAt());
    }

    public static DocumentChecklistItem toDomain(DocumentChecklistItemEntity entity) {
        return new DocumentChecklistItem(
                entity.getId(),
                entity.getLegalCaseId(),
                entity.getChecklistRuleId(),
                entity.getStatus(),
                entity.getDocumentId(),
                entity.getEvaluatedAt());
    }
}
