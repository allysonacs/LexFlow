package com.lexflow.domain.document;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Metadados de um arquivo anexado a uma demanda. O binário em si vive no storage (Prompt 06) e nunca
 * trafega pelo domínio.
 *
 * @param documentType tipo do documento informado pelo requisitante no upload, no formato de
 *     {@link DocumentTypeCode}; opcional. É o que vincula o documento a um item de checklist (seção 9)
 */
public record Document(
        UUID id,
        UUID legalCaseId,
        String fileName,
        String storagePath,
        String mimeType,
        Sha256Checksum checksum,
        Instant uploadedAt,
        String documentType) {

    public Document {
        Objects.requireNonNull(id, "id não pode ser nulo");
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        Objects.requireNonNull(checksum, "checksum não pode ser nulo");
        Objects.requireNonNull(uploadedAt, "uploadedAt não pode ser nulo");
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName é obrigatório");
        }
        if (storagePath == null || storagePath.isBlank()) {
            throw new IllegalArgumentException("storagePath é obrigatório");
        }
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType é obrigatório");
        }
        documentType = DocumentTypeCode.normalizeOptional(documentType);
    }

    /** Documento sem tipo informado. */
    public Document(
            UUID id,
            UUID legalCaseId,
            String fileName,
            String storagePath,
            String mimeType,
            Sha256Checksum checksum,
            Instant uploadedAt) {
        this(id, legalCaseId, fileName, storagePath, mimeType, checksum, uploadedAt, null);
    }

    /** Indica se o documento é do tipo informado. */
    public boolean isOfType(String documentTypeCode) {
        return documentType != null && documentType.equals(documentTypeCode);
    }

    /** Indica se o arquivo é idêntico a outro já anexado à mesma demanda. */
    public boolean hasSameContentAs(Document other) {
        return other != null && legalCaseId.equals(other.legalCaseId) && checksum.equals(other.checksum);
    }
}
