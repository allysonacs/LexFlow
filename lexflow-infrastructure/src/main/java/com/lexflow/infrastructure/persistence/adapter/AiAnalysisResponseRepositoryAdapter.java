package com.lexflow.infrastructure.persistence.adapter;

import com.lexflow.application.analysis.AiAnalysisResponseRepository;
import com.lexflow.domain.ai.AiAnalysisResponse;
import com.lexflow.domain.ai.QuestionKey;
import com.lexflow.infrastructure.persistence.mapper.AiAnalysisResponseMapper;
import com.lexflow.infrastructure.persistence.repository.AiAnalysisResponseJpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Implementação JPA da porta {@link AiAnalysisResponseRepository}. */
@Component
public class AiAnalysisResponseRepositoryAdapter implements AiAnalysisResponseRepository {

    private final AiAnalysisResponseJpaRepository jpaRepository;

    public AiAnalysisResponseRepositoryAdapter(AiAnalysisResponseJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public AiAnalysisResponse save(AiAnalysisResponse response) {
        return AiAnalysisResponseMapper.toDomain(
                jpaRepository.save(AiAnalysisResponseMapper.toEntity(response)));
    }

    @Override
    public List<AiAnalysisResponse> findByLegalCaseId(UUID legalCaseId) {
        return jpaRepository.findByLegalCaseIdOrderByCreatedAtAscQuestionKeyAsc(legalCaseId).stream()
                .map(AiAnalysisResponseMapper::toDomain)
                .toList();
    }

    @Override
    public void deleteByLegalCaseId(UUID legalCaseId) {
        jpaRepository.deleteAll(jpaRepository.findByLegalCaseId(legalCaseId));
    }

    @Override
    public Optional<AiAnalysisResponse> findByLegalCaseIdAndQuestionKey(UUID legalCaseId, QuestionKey questionKey) {
        return jpaRepository
                .findByLegalCaseIdAndQuestionKey(legalCaseId, questionKey)
                .map(AiAnalysisResponseMapper::toDomain);
    }
}
