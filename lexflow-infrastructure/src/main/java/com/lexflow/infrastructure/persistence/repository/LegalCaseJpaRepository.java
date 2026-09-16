package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.infrastructure.persistence.entity.LegalCaseEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acesso à tabela {@code legal_cases}.
 *
 * <p>A listagem por status é sempre paginada, conforme a seção 11 da base de conhecimento.
 */
public interface LegalCaseJpaRepository extends JpaRepository<LegalCaseEntity, UUID> {

    Page<LegalCaseEntity> findByStatus(LegalCaseStatus status, Pageable pageable);

    Optional<LegalCaseEntity> findByExternalReference(String externalReference);
}
