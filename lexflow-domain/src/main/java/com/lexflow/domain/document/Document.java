package com.lexflow.domain.document;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Metadados de um arquivo anexado a uma demanda. O binário em si vive no storage (Prompt 06) e nunca
 * trafega pelo domínio.
 */
public record Document(
        UUID id,
        UUID legalCaseId,
        String fileName,
        String storagePath,
        String mimeType,
        Sha256Checksum checksum,
        Instant uploadedAt) {

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
    }

    /** Indica se o arquivo é idêntico a outro já anexado à mesma demanda. */
    public boolean hasSameContentAs(Document other) {
        return other != null && legalCaseId.equals(other.legalCaseId) && checksum.equals(other.checksum);
    }
}
