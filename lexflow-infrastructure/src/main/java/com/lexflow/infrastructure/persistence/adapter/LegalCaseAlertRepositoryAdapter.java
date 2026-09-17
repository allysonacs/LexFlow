package com.lexflow.infrastructure.persistence.adapter;

import com.lexflow.application.alert.LegalCaseAlertRepository;
import com.lexflow.domain.alert.LegalCaseAlert;
import com.lexflow.infrastructure.persistence.mapper.LegalCaseAlertMapper;
import com.lexflow.infrastructure.persistence.repository.LegalCaseAlertJpaRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Implementação JPA da porta {@link LegalCaseAlertRepository}. */
@Component
public class LegalCaseAlertRepositoryAdapter implements LegalCaseAlertRepository {

    private final LegalCaseAlertJpaRepository jpaRepository;

    public LegalCaseAlertRepositoryAdapter(LegalCaseAlertJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(LegalCaseAlert alert) {
        jpaRepository.save(LegalCaseAlertMapper.toEntity(alert));
    }

    @Override
    public List<LegalCaseAlert> findByLegalCaseId(UUID legalCaseId) {
        return jpaRepository.findByLegalCaseIdOrderByCreatedAtAscIdAsc(legalCaseId).stream()
                .map(LegalCaseAlertMapper::toDomain)
                .toList();
    }
}
