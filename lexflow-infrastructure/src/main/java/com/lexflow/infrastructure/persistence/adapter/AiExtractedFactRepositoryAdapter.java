package com.lexflow.infrastructure.persistence.adapter;

import com.lexflow.application.fact.AiExtractedFactRepository;
import com.lexflow.domain.ai.AiExtractedFact;
import com.lexflow.infrastructure.persistence.mapper.AiExtractedFactMapper;
import com.lexflow.infrastructure.persistence.repository.AiExtractedFactJpaRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Implementação JPA da porta {@link AiExtractedFactRepository}. */
@Component
public class AiExtractedFactRepositoryAdapter implements AiExtractedFactRepository {

    private final AiExtractedFactJpaRepository jpaRepository;

    public AiExtractedFactRepositoryAdapter(AiExtractedFactJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(AiExtractedFact fact) {
        jpaRepository.save(AiExtractedFactMapper.toEntity(fact));
    }

    @Override
    public List<AiExtractedFact> findByLegalCaseId(UUID legalCaseId) {
        return jpaRepository.findByLegalCaseId(legalCaseId).stream()
                .map(AiExtractedFactMapper::toDomain)
                .toList();
    }
}
