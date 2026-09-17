package com.lexflow.api.audit;

import com.lexflow.domain.audit.AuditLog;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Uma linha da trilha de auditoria, no contrato REST.
 *
 * @param actor quem executou: um usuário identificado, {@code SYSTEM} ou {@code AI}
 * @param payload o que mudou, sempre em metadados — status, tipo de decisão, identificadores
 */
public record AuditLogEntryResponse(
        UUID id,
        String entityType,
        UUID entityId,
        String action,
        String actor,
        Map<String, String> payload,
        Instant occurredAt) {

    public static AuditLogEntryResponse from(AuditLog auditLog) {
        return new AuditLogEntryResponse(
                auditLog.id(),
                auditLog.entityType().name(),
                auditLog.entityId(),
                auditLog.action().name(),
                auditLog.actor(),
                auditLog.payload(),
                auditLog.occurredAt());
    }
}
