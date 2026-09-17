package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.infrastructure.persistence.entity.AuditLogEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acesso à tabela {@code audit_logs}.
 *
 * <p>A trilha é append-only: nenhuma operação de alteração ou remoção é usada aqui, mesmo que o
 * {@code JpaRepository} as herde. A proteção de verdade está no banco, no gatilho da migration V9.
 */
public interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, UUID> {

    List<AuditLogEntity> findByEntityTypeAndEntityIdOrderByOccurredAtAsc(String entityType, UUID entityId);

    /** Linha do tempo de uma demanda, incluindo o que foi registrado sobre respostas e decisões. */
    List<AuditLogEntity> findByLegalCaseIdOrderByOccurredAtAscIdAsc(UUID legalCaseId);
}
