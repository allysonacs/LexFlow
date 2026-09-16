package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.infrastructure.persistence.entity.ProcessingEventEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso à tabela {@code processing_events}, usada para garantir idempotência (seção 11). */
public interface ProcessingEventJpaRepository extends JpaRepository<ProcessingEventEntity, UUID> {

    Optional<ProcessingEventEntity> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);
}
