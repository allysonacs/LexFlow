package com.lexflow.infrastructure.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.application.audit.AuditLogReader;
import com.lexflow.application.audit.AuditLogWriter;
import com.lexflow.domain.audit.AuditAction;
import com.lexflow.domain.audit.AuditLog;
import com.lexflow.domain.audit.AuditedEntity;
import com.lexflow.infrastructure.persistence.entity.AuditLogEntity;
import com.lexflow.infrastructure.persistence.repository.AuditLogJpaRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Trilha de auditoria sobre a tabela {@code audit_logs}.
 *
 * <p><strong>Gravar nunca lança.</strong> É o que torna a trilha observadora: uma falha ao registrar
 * a linha aparece no log da aplicação e nada mais. O contrário faria uma indisponibilidade da
 * auditoria derrubar a ingestão de demandas — e uma regra de negócio passaria a depender dela, o que
 * o Prompt 16 proíbe.
 *
 * <p>Não há aqui nenhuma operação de alteração ou remoção, e o banco recusaria as duas de qualquer
 * forma (migration V9).
 */
@Component
public class JpaAuditLogAdapter implements AuditLogWriter, AuditLogReader {

    private static final Logger log = LoggerFactory.getLogger(JpaAuditLogAdapter.class);

    private static final TypeReference<Map<String, String>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final AuditLogJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;

    public JpaAuditLogAdapter(AuditLogJpaRepository jpaRepository, ObjectMapper objectMapper) {
        this.jpaRepository = jpaRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(AuditLog auditLog) {
        try {
            jpaRepository.save(new AuditLogEntity(
                    auditLog.id(),
                    auditLog.entityType().name(),
                    auditLog.entityId(),
                    auditLog.legalCaseId(),
                    auditLog.action().name(),
                    auditLog.actor(),
                    objectMapper.writeValueAsString(auditLog.payload()),
                    auditLog.occurredAt()));
        } catch (Exception e) {
            log.error(
                    "Falha ao gravar a trilha de auditoria: entidade={} id={} ação={} ator={}",
                    auditLog.entityType(),
                    auditLog.entityId(),
                    auditLog.action(),
                    auditLog.actor(),
                    e);
        }
    }

    @Override
    public List<AuditLog> findByLegalCaseId(UUID legalCaseId) {
        return jpaRepository.findByLegalCaseIdOrderByOccurredAtAscIdAsc(legalCaseId).stream()
                .map(this::toDomain)
                .toList();
    }

    private AuditLog toDomain(AuditLogEntity entity) {
        return new AuditLog(
                entity.getId(),
                AuditedEntity.valueOf(entity.getEntityType()),
                entity.getEntityId(),
                entity.getLegalCaseId(),
                AuditAction.valueOf(entity.getAction()),
                entity.getActor(),
                readPayload(entity),
                entity.getOccurredAt());
    }

    private Map<String, String> readPayload(AuditLogEntity entity) {
        if (entity.getPayload() == null || entity.getPayload().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(entity.getPayload(), PAYLOAD_TYPE);
        } catch (Exception e) {
            // Uma linha antiga com payload em outro formato não pode impedir a leitura da trilha.
            log.warn("Payload de auditoria ilegível na linha {}", entity.getId(), e);
            return Map.of();
        }
    }
}
