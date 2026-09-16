package com.lexflow.infrastructure.persistence.adapter;

import com.lexflow.application.legalcase.LegalCaseRepository;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.infrastructure.persistence.mapper.LegalCaseMapper;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Implementação JPA da porta {@link LegalCaseRepository}. */
@Component
public class LegalCaseRepositoryAdapter implements LegalCaseRepository {

    private final LegalCaseJpaRepository jpaRepository;

    public LegalCaseRepositoryAdapter(LegalCaseJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public LegalCase save(LegalCase legalCase) {
        return LegalCaseMapper.toDomain(jpaRepository.save(LegalCaseMapper.toEntity(legalCase)));
    }

    @Override
    public Optional<LegalCase> findById(UUID id) {
        return jpaRepository.findById(id).map(LegalCaseMapper::toDomain);
    }
}
