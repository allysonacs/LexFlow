package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.infrastructure.persistence.entity.DocumentChecklistItemEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso à tabela {@code document_checklist_items}. */
public interface DocumentChecklistItemJpaRepository extends JpaRepository<DocumentChecklistItemEntity, UUID> {

    List<DocumentChecklistItemEntity> findByLegalCaseId(UUID legalCaseId);

    boolean existsByChecklistRuleId(UUID checklistRuleId);
}
