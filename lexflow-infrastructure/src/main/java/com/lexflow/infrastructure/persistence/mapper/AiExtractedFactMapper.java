package com.lexflow.infrastructure.persistence.mapper;

import com.lexflow.domain.ai.AiExtractedFact;
import com.lexflow.infrastructure.persistence.entity.AiExtractedFactEntity;

/** Converte entre {@link AiExtractedFact} e {@link AiExtractedFactEntity}. */
public final class AiExtractedFactMapper {

    private AiExtractedFactMapper() {
        // classe utilitária
    }

    public static AiExtractedFactEntity toEntity(AiExtractedFact fact) {
        return new AiExtractedFactEntity(
                fact.id(),
                fact.legalCaseId(),
                fact.documentId(),
                fact.extractedJson(),
                fact.modelVersion(),
                fact.extractedAt());
    }

    public static AiExtractedFact toDomain(AiExtractedFactEntity entity) {
        return new AiExtractedFact(
                entity.getId(),
                entity.getLegalCaseId(),
                entity.getDocumentId(),
                entity.getExtractedJson(),
                entity.getModelVersion(),
                entity.getExtractedAt());
    }
}
