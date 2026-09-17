package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.infrastructure.persistence.entity.DocumentTextContentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso à tabela {@code document_text_contents}. */
public interface DocumentTextContentJpaRepository extends JpaRepository<DocumentTextContentEntity, UUID> {

    List<DocumentTextContentEntity> findByLegalCaseId(UUID legalCaseId);

    Optional<DocumentTextContentEntity> findByDocumentId(UUID documentId);
}
