package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.infrastructure.persistence.entity.AuditLogEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acesso à tabela {@code audit_logs}.
 *
 * <p>A trilha é append-only: nenhuma operação de alteração ou remoção deve ser exposta aqui, mesmo
 * que o {@code JpaRepository} as herde (a proteção no banco entra no Prompt 16).
 */
public interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, UUID> {

    List<AuditLogEntity> findByEntityTypeAndEntityIdOrderByOccurredAtAsc(String entityType, UUID entityId);
}
