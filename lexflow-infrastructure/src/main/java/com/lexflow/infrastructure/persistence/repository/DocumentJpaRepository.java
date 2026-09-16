package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.infrastructure.persistence.entity.DocumentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso à tabela {@code documents}. */
public interface DocumentJpaRepository extends JpaRepository<DocumentEntity, UUID> {

    List<DocumentEntity> findByLegalCaseId(UUID legalCaseId);

    /** Sustenta a detecção de reenvio do mesmo arquivo na mesma demanda (Prompt 06). */
    Optional<DocumentEntity> findByLegalCaseIdAndChecksumSha256(UUID legalCaseId, String checksumSha256);
}
