package com.lexflow.infrastructure.persistence.entity;

import com.lexflow.domain.legalcase.LegalCaseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapeamento da tabela {@code legal_case_status_history}: uma linha por transição de status.
 *
 * <p>{@code previousStatus} é nulo na criação da demanda, quando ela nasce em {@code RECEIVED}.
 */
@Entity
@Table(name = "legal_case_status_history")
public class LegalCaseStatusHistoryEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "legal_case_id", nullable = false)
    private UUID legalCaseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 50)
    private LegalCaseStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 50)
    private LegalCaseStatus newStatus;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "changed_by")
    private String changedBy;

    @Column(name = "reason")
    private String reason;

    protected LegalCaseStatusHistoryEntity() {
        // exigido pelo JPA
    }

    public LegalCaseStatusHistoryEntity(
            UUID id,
            UUID legalCaseId,
            LegalCaseStatus previousStatus,
            LegalCaseStatus newStatus,
            Instant changedAt,
            String changedBy,
            String reason) {
        this.id = id;
        this.legalCaseId = legalCaseId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.changedAt = changedAt;
        this.changedBy = changedBy;
        this.reason = reason;
    }

    public UUID getId() {
        return id;
    }

    public UUID getLegalCaseId() {
        return legalCaseId;
    }

    public LegalCaseStatus getPreviousStatus() {
        return previousStatus;
    }

    public LegalCaseStatus getNewStatus() {
        return newStatus;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public String getReason() {
        return reason;
    }
}
