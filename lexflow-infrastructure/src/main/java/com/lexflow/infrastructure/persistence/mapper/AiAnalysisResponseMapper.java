package com.lexflow.infrastructure.persistence.mapper;

import com.lexflow.domain.ai.AiAnalysisResponse;
import com.lexflow.domain.ai.ConfidenceScore;
import com.lexflow.domain.ai.VerificationStatus;
import com.lexflow.infrastructure.persistence.entity.AiAnalysisResponseEntity;

/**
 * Converte entre {@link AiAnalysisResponse} e {@link AiAnalysisResponseEntity}.
 *
 * <p>Enquanto a coluna {@code verification_status} não existe (ela chega na migration do Prompt 14),
 * toda resposta lida do banco volta como {@link VerificationStatus#NOT_VERIFIED}.
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
                VerificationStatus.NOT_VERIFIED,
                entity.getCreatedAt());
    }
}
