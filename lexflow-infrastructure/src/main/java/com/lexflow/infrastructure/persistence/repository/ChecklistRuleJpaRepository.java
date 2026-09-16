package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.domain.legalcase.LegalCaseType;
import com.lexflow.infrastructure.persistence.entity.ChecklistRuleEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso à tabela {@code checklist_rules}. */
public interface ChecklistRuleJpaRepository extends JpaRepository<ChecklistRuleEntity, UUID> {

    List<ChecklistRuleEntity> findByCaseType(LegalCaseType caseType);
}
