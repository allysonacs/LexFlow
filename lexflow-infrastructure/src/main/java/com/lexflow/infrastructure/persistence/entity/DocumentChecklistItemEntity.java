package com.lexflow.infrastructure.persistence.entity;

import com.lexflow.domain.checklist.ChecklistItemStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mapeamento da tabela {@code document_checklist_items}. */
@Entity
@Table(name = "document_checklist_items")
public class DocumentChecklistItemEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "legal_case_id", nullable = false)
    private UUID legalCaseId;

    @Column(name = "checklist_rule_id", nullable = false)
    private UUID checklistRuleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChecklistItemStatus status;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "evaluated_at")
    private Instant evaluatedAt;

    protected DocumentChecklistItemEntity() {
        // exigido pelo JPA
    }

    public DocumentChecklistItemEntity(
            UUID id,
            UUID legalCaseId,
            UUID checklistRuleId,
            ChecklistItemStatus status,
            UUID documentId,
            Instant evaluatedAt) {
        this.id = id;
        this.legalCaseId = legalCaseId;
        this.checklistRuleId = checklistRuleId;
        this.status = status;
        this.documentId = documentId;
        this.evaluatedAt = evaluatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getLegalCaseId() {
        return legalCaseId;
    }

    public UUID getChecklistRuleId() {
        return checklistRuleId;
    }

    public ChecklistItemStatus getStatus() {
        return status;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }
}
