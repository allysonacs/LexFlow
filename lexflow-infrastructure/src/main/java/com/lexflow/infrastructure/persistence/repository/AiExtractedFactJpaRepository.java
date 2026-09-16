package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.infrastructure.persistence.entity.AiExtractedFactEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso à tabela {@code ai_extracted_facts}. */
public interface AiExtractedFactJpaRepository extends JpaRepository<AiExtractedFactEntity, UUID> {

    List<AiExtractedFactEntity> findByLegalCaseId(UUID legalCaseId);

    List<AiExtractedFactEntity> findByDocumentId(UUID documentId);
}
