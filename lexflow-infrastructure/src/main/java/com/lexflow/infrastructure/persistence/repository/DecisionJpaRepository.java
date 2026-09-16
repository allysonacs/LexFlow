package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.infrastructure.persistence.entity.DecisionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso à tabela {@code decisions}. */
public interface DecisionJpaRepository extends JpaRepository<DecisionEntity, UUID> {

    List<DecisionEntity> findByLegalCaseIdOrderByDecidedAtAsc(UUID legalCaseId);
}
