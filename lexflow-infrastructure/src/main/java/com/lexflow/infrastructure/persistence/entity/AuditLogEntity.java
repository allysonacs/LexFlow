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
 * Mapeamento da tabela {@code audit_logs}.
 *
 * <p>A tabela é append-only por definição de negócio (seção 12), e o banco garante isso: a migration
 * V9 instala um gatilho que recusa {@code UPDATE}, {@code DELETE} e {@code TRUNCATE}. A entidade não
 * expõe nenhum setter, e nada no sistema tenta alterar uma linha já gravada.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "legal_case_id")
    private UUID legalCaseId;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "actor", nullable = false)
    private String actor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditLogEntity() {
        // exigido pelo JPA
    }

    public AuditLogEntity(
            UUID id,
            String entityType,
            UUID entityId,
            UUID legalCaseId,
            String action,
            String actor,
            String payload,
            Instant occurredAt) {
        this.id = id;
        this.entityType = entityType;
        this.entityId = entityId;
        this.legalCaseId = legalCaseId;
        this.action = action;
        this.actor = actor;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public UUID getLegalCaseId() {
        return legalCaseId;
    }

    public String getAction() {
        return action;
    }

    public String getActor() {
        return actor;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
