package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.domain.legalcase.LegalCaseType;
import com.lexflow.infrastructure.persistence.entity.ChecklistRuleEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso à tabela {@code checklist_rules}. */
public interface ChecklistRuleJpaRepository extends JpaRepository<ChecklistRuleEntity, UUID> {

    List<ChecklistRuleEntity> findByCaseType(LegalCaseType caseType);

    List<ChecklistRuleEntity> findByCaseTypeOrderByRequiredDocumentTypeAsc(LegalCaseType caseType);

    Page<ChecklistRuleEntity> findByCaseType(LegalCaseType caseType, Pageable pageable);

    boolean existsByCaseTypeAndRequiredDocumentType(LegalCaseType caseType, String requiredDocumentType);
}
