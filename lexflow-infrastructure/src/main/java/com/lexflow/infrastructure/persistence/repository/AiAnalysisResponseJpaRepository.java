package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.domain.ai.QuestionKey;
import com.lexflow.infrastructure.persistence.entity.AiAnalysisResponseEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso à tabela {@code ai_analysis_responses}. */
public interface AiAnalysisResponseJpaRepository extends JpaRepository<AiAnalysisResponseEntity, UUID> {

    List<AiAnalysisResponseEntity> findByLegalCaseId(UUID legalCaseId);

    /** Respostas na ordem em que foram geradas, que é a ordem em que as perguntas são feitas. */
    List<AiAnalysisResponseEntity> findByLegalCaseIdOrderByCreatedAtAscQuestionKeyAsc(UUID legalCaseId);

    Optional<AiAnalysisResponseEntity> findByLegalCaseIdAndQuestionKey(UUID legalCaseId, QuestionKey questionKey);
}
