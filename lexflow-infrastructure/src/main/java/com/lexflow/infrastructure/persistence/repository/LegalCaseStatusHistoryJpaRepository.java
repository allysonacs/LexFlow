package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.infrastructure.persistence.entity.LegalCaseStatusHistoryEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso à tabela {@code legal_case_status_history}. */
public interface LegalCaseStatusHistoryJpaRepository extends JpaRepository<LegalCaseStatusHistoryEntity, UUID> {

    List<LegalCaseStatusHistoryEntity> findByLegalCaseIdOrderByChangedAtAsc(UUID legalCaseId);
}
