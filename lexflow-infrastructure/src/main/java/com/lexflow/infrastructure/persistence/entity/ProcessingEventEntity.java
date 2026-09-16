package com.lexflow.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Mapeamento da tabela {@code processing_events}, base da idempotência do consumo de fila (seção 11).
 *
 * <p>A unicidade de {@code idempotencyKey} é garantida por índice único no banco, e não por consulta
 * na aplicação: é isso que impede duas réplicas do worker de processarem a mesma mensagem.
 */
@Entity
@Table(name = "processing_events")
public class ProcessingEventEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProcessingEventStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected ProcessingEventEntity() {
        // exigido pelo JPA
    }

    public ProcessingEventEntity(
            UUID id,
            String eventType,
            UUID aggregateId,
            String idempotencyKey,
            ProcessingEventStatus status,
            String payload,
            Instant createdAt,
            Instant processedAt) {
        this.id = id;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.payload = payload;
        this.createdAt = createdAt;
        this.processedAt = processedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public ProcessingEventStatus getStatus() {
        return status;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
