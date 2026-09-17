package com.lexflow.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Mapeamento da tabela {@code ai_extracted_facts}.
 *
 * <p>{@code extractedJson} é gravado na coluna {@code jsonb} como texto cru: o JSON já vem validado
 * contra o schema pela camada de aplicação (Prompt 11), e guardá-lo assim preserva exatamente o que
 * o modelo devolveu.
 */
@Entity
@Table(name = "ai_extracted_facts")
public class AiExtractedFactEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "legal_case_id", nullable = false)
    private UUID legalCaseId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_json", nullable = false)
    private String extractedJson;

    @Column(name = "model_version", nullable = false, length = 100)
    private String modelVersion;

    @Column(name = "prompt_version_id", nullable = false)
    private UUID promptVersionId;

    @Column(name = "extracted_at", nullable = false)
    private Instant extractedAt;

    protected AiExtractedFactEntity() {
        // exigido pelo JPA
    }

    public AiExtractedFactEntity(
            UUID id,
            UUID legalCaseId,
            UUID documentId,
            String extractedJson,
            String modelVersion,
            UUID promptVersionId,
            Instant extractedAt) {
        this.id = id;
        this.legalCaseId = legalCaseId;
        this.documentId = documentId;
        this.extractedJson = extractedJson;
        this.modelVersion = modelVersion;
        this.promptVersionId = promptVersionId;
        this.extractedAt = extractedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getLegalCaseId() {
        return legalCaseId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public String getExtractedJson() {
        return extractedJson;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public UUID getPromptVersionId() {
        return promptVersionId;
    }

    public Instant getExtractedAt() {
        return extractedAt;
    }
}
