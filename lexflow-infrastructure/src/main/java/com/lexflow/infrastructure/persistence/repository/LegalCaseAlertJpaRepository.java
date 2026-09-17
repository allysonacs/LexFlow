package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.infrastructure.persistence.entity.LegalCaseAlertEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso à tabela {@code legal_case_alerts}. */
public interface LegalCaseAlertJpaRepository extends JpaRepository<LegalCaseAlertEntity, UUID> {

    List<LegalCaseAlertEntity> findByLegalCaseIdOrderByCreatedAtAscIdAsc(UUID legalCaseId);
}
