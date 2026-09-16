package com.lexflow.infrastructure.persistence.entity;

import com.lexflow.domain.decision.DecisionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mapeamento da tabela {@code decisions}. */
@Entity
@Table(name = "decisions")
public class DecisionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "legal_case_id", nullable = false)
    private UUID legalCaseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_type", nullable = false, length = 30)
    private DecisionType decisionType;

    @Column(name = "decided_by", nullable = false)
    private String decidedBy;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(name = "comments")
    private String comments;

    protected DecisionEntity() {
        // exigido pelo JPA
    }

    public DecisionEntity(
            UUID id,
            UUID legalCaseId,
            DecisionType decisionType,
            String decidedBy,
            Instant decidedAt,
            String comments) {
        this.id = id;
        this.legalCaseId = legalCaseId;
        this.decisionType = decisionType;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.comments = comments;
    }

    public UUID getId() {
        return id;
    }

    public UUID getLegalCaseId() {
        return legalCaseId;
    }

    public DecisionType getDecisionType() {
        return decisionType;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getComments() {
        return comments;
    }
}
