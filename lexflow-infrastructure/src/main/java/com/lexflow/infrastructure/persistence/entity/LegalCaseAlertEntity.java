package com.lexflow.infrastructure.persistence.entity;

import com.lexflow.domain.alert.LegalCaseAlertType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mapeamento da tabela {@code legal_case_alerts}. */
@Entity
@Table(name = "legal_case_alerts")
public class LegalCaseAlertEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "legal_case_id", nullable = false)
    private UUID legalCaseId;

    @Column(name = "document_id")
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, length = 50)
    private LegalCaseAlertType alertType;

    @Column(name = "message", nullable = false, length = 2000)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected LegalCaseAlertEntity() {
        // exigido pelo JPA
    }

    public LegalCaseAlertEntity(
            UUID id,
            UUID legalCaseId,
            UUID documentId,
            LegalCaseAlertType alertType,
            String message,
            Instant createdAt,
            Instant resolvedAt) {
        this.id = id;
        this.legalCaseId = legalCaseId;
        this.documentId = documentId;
        this.alertType = alertType;
        this.message = message;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
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

    public LegalCaseAlertType getAlertType() {
        return alertType;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
