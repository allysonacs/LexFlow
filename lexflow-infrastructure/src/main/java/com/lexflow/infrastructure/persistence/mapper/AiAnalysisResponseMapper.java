package com.lexflow.infrastructure.persistence.mapper;

import com.lexflow.domain.ai.AiAnalysisResponse;
import com.lexflow.domain.ai.ConfidenceScore;
import com.lexflow.domain.ai.VerificationStatus;
import com.lexflow.infrastructure.persistence.entity.AiAnalysisResponseEntity;

/**
 * Converte entre {@link AiAnalysisResponse} e {@link AiAnalysisResponseEntity}.
 *
 * <p>O resultado da segunda checagem ({@link VerificationStatus}) e a sua justificativa vão e voltam
 * do banco desde a migration V8 (Prompt 14).
 */
public final class AiAnalysisResponseMapper {

    private AiAnalysisResponseMapper() {
        // classe utilitária
    }

    public static AiAnalysisResponseEntity toEntity(AiAnalysisResponse response) {
        return new AiAnalysisResponseEntity(
                response.id(),
                response.legalCaseId(),
                response.questionKey(),
                response.answerText(),
                response.confidenceScore().value(),
                response.citedChunks(),
                response.answerSource(),
                response.modelVersion(),
                response.promptVersionId(),
                response.verificationStatus(),
                response.verificationNotes(),
                response.createdAt());
    }

    public static AiAnalysisResponse toDomain(AiAnalysisResponseEntity entity) {
        return new AiAnalysisResponse(
                entity.getId(),
                entity.getLegalCaseId(),
                entity.getQuestionKey(),
                entity.getAnswerText(),
                ConfidenceScore.of(entity.getConfidenceScore()),
                entity.getCitedChunks(),
                entity.getAnswerSource(),
                entity.getModelVersion(),
                entity.getPromptVersionId(),
                entity.getVerificationStatus(),
                entity.getVerificationNotes(),
                entity.getCreatedAt());
    }
}
