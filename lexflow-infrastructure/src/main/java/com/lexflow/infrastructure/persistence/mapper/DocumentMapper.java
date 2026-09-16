package com.lexflow.infrastructure.persistence.mapper;

import com.lexflow.domain.document.Document;
import com.lexflow.domain.document.Sha256Checksum;
import com.lexflow.infrastructure.persistence.entity.DocumentEntity;

/** Converte entre {@link Document} e {@link DocumentEntity}. */
public final class DocumentMapper {

    private DocumentMapper() {
        // classe utilitária
    }

    public static DocumentEntity toEntity(Document document) {
        return new DocumentEntity(
                document.id(),
                document.legalCaseId(),
                document.fileName(),
                document.storagePath(),
                document.mimeType(),
                document.checksum().value(),
                document.uploadedAt(),
                document.documentType());
    }

    public static Document toDomain(DocumentEntity entity) {
        return new Document(
                entity.getId(),
                entity.getLegalCaseId(),
                entity.getFileName(),
                entity.getStoragePath(),
                entity.getMimeType(),
                Sha256Checksum.of(entity.getChecksumSha256()),
                entity.getUploadedAt(),
                entity.getDocumentType());
    }
}
