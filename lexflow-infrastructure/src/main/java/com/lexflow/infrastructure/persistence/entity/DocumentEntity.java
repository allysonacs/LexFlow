package com.lexflow.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mapeamento da tabela {@code documents}: apenas metadados, nunca o binário do arquivo. */
@Entity
@Table(name = "documents")
public class DocumentEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "legal_case_id", nullable = false)
    private UUID legalCaseId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "storage_path", nullable = false, length = 1024)
    private String storagePath;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected DocumentEntity() {
        // exigido pelo JPA
    }

    public DocumentEntity(
            UUID id,
            UUID legalCaseId,
            String fileName,
            String storagePath,
            String mimeType,
            String checksumSha256,
            Instant uploadedAt) {
        this.id = id;
        this.legalCaseId = legalCaseId;
        this.fileName = fileName;
        this.storagePath = storagePath;
        this.mimeType = mimeType;
        this.checksumSha256 = checksumSha256;
        this.uploadedAt = uploadedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getLegalCaseId() {
        return legalCaseId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
