package com.lexflow.infrastructure.persistence.entity;

import com.lexflow.domain.document.TextExtractionMethod;
import com.lexflow.domain.document.TextExtractionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapeamento da tabela {@code document_text_contents}: o texto extraído de um documento.
 *
 * <p>O {@code toString} herdado de {@link Object} é mantido de propósito — um {@code toString} gerado
 * com todos os campos levaria o conteúdo do documento para o log (seção 12).
 */
@Entity
@Table(name = "document_text_contents")
public class DocumentTextContentEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "legal_case_id", nullable = false)
    private UUID legalCaseId;

    @Column(name = "content", columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_method", length = 20)
    private TextExtractionMethod extractionMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TextExtractionStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "extracted_at", nullable = false)
    private Instant extractedAt;

    protected DocumentTextContentEntity() {
        // exigido pelo JPA
    }

    public DocumentTextContentEntity(
            UUID id,
            UUID documentId,
            UUID legalCaseId,
            String content,
            TextExtractionMethod extractionMethod,
            TextExtractionStatus status,
            String failureReason,
            Instant extractedAt) {
        this.id = id;
        this.documentId = documentId;
        this.legalCaseId = legalCaseId;
        this.content = content;
        this.extractionMethod = extractionMethod;
        this.status = status;
        this.failureReason = failureReason;
        this.extractedAt = extractedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getLegalCaseId() {
        return legalCaseId;
    }

    public String getContent() {
        return content;
    }

    public TextExtractionMethod getExtractionMethod() {
        return extractionMethod;
    }

    public TextExtractionStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getExtractedAt() {
        return extractedAt;
    }
}
