package com.lexflow.infrastructure.persistence.mapper;

import com.lexflow.domain.document.DocumentTextContent;
import com.lexflow.infrastructure.persistence.entity.DocumentTextContentEntity;

/** Converte entre {@link DocumentTextContent} e {@link DocumentTextContentEntity}. */
public final class DocumentTextContentMapper {

    private DocumentTextContentMapper() {
        // classe utilitária
    }

    public static DocumentTextContentEntity toEntity(DocumentTextContent textContent) {
        return new DocumentTextContentEntity(
                textContent.id(),
                textContent.documentId(),
                textContent.legalCaseId(),
                textContent.content(),
                textContent.method(),
                textContent.status(),
                textContent.failureReason(),
                textContent.extractedAt());
    }

    public static DocumentTextContent toDomain(DocumentTextContentEntity entity) {
        return new DocumentTextContent(
                entity.getId(),
                entity.getDocumentId(),
                entity.getLegalCaseId(),
                entity.getContent(),
                entity.getExtractionMethod(),
                entity.getStatus(),
                entity.getFailureReason(),
                entity.getExtractedAt());
    }
}
