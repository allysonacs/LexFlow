package com.lexflow.infrastructure.persistence.adapter;

import com.lexflow.application.review.DecisionRepository;
import com.lexflow.domain.decision.Decision;
import com.lexflow.infrastructure.persistence.mapper.DecisionMapper;
import com.lexflow.infrastructure.persistence.repository.DecisionJpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Implementação JPA da porta {@link DecisionRepository}. */
@Component
public class DecisionRepositoryAdapter implements DecisionRepository {

    private final DecisionJpaRepository jpaRepository;

    public DecisionRepositoryAdapter(DecisionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Decision save(Decision decision) {
        return DecisionMapper.toDomain(jpaRepository.save(DecisionMapper.toEntity(decision)));
    }

    @Override
    public Optional<Decision> findById(UUID id) {
        return jpaRepository.findById(id).map(DecisionMapper::toDomain);
    }

    @Override
    public List<Decision> findByLegalCaseId(UUID legalCaseId) {
        return jpaRepository.findByLegalCaseIdOrderByDecidedAtAsc(legalCaseId).stream()
                .map(DecisionMapper::toDomain)
                .toList();
    }
}
