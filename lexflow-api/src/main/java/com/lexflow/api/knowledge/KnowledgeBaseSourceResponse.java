package com.lexflow.api.knowledge;

import com.lexflow.application.knowledge.KnowledgeBaseIngestionResult;
import com.lexflow.domain.knowledge.KnowledgeBaseSource;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Fonte normativa no contrato REST.
 *
 * @param chunkCount trechos indexados; nulo nas listagens, onde a contagem não é calculada
 * @param indexedCharacters caracteres indexados; nulo pelo mesmo motivo
 */
public record KnowledgeBaseSourceResponse(
        UUID id,
        String title,
        String sourceType,
        LocalDate effectiveDate,
        Integer chunkCount,
        Integer indexedCharacters) {

    public static KnowledgeBaseSourceResponse from(KnowledgeBaseSource source) {
        return new KnowledgeBaseSourceResponse(
                source.id(), source.title(), source.sourceType().name(), source.effectiveDate(), null, null);
    }

    public static KnowledgeBaseSourceResponse from(KnowledgeBaseIngestionResult result) {
        KnowledgeBaseSource source = result.source();
        return new KnowledgeBaseSourceResponse(
                source.id(),
                source.title(),
                source.sourceType().name(),
                source.effectiveDate(),
                result.chunkCount(),
                result.indexedCharacters());
    }
}
