package com.lexflow.infrastructure.persistence.mapper;

import com.lexflow.application.knowledge.RetrievedChunk;
import com.lexflow.domain.knowledge.Embedding;
import com.lexflow.domain.knowledge.KnowledgeBaseChunk;
import com.lexflow.domain.knowledge.KnowledgeBaseSource;
import com.lexflow.domain.knowledge.KnowledgeBaseSourceType;
import com.lexflow.infrastructure.persistence.entity.KnowledgeBaseChunkEntity;
import com.lexflow.infrastructure.persistence.entity.KnowledgeBaseSourceEntity;
import com.lexflow.infrastructure.persistence.repository.ChunkSimilarityRow;
import java.util.StringJoiner;

/** Conversões entre o domínio da base normativa e as entidades JPA. */
public final class KnowledgeBaseMapper {

    private KnowledgeBaseMapper() {
        // classe utilitária
    }

    public static KnowledgeBaseSourceEntity toEntity(KnowledgeBaseSource source) {
        return new KnowledgeBaseSourceEntity(
                source.id(), source.title(), source.sourceType().name(), source.effectiveDate());
    }

    public static KnowledgeBaseSource toDomain(KnowledgeBaseSourceEntity entity) {
        return new KnowledgeBaseSource(
                entity.getId(),
                entity.getTitle(),
                KnowledgeBaseSourceType.of(entity.getSourceType()),
                entity.getEffectiveDate());
    }

    public static KnowledgeBaseChunkEntity toEntity(KnowledgeBaseChunk chunk) {
        return new KnowledgeBaseChunkEntity(
                chunk.id(),
                chunk.sourceId(),
                chunk.chunkIndex(),
                chunk.content(),
                chunk.embedding() == null ? null : chunk.embedding().toArray());
    }

    public static KnowledgeBaseChunk toDomain(KnowledgeBaseChunkEntity entity) {
        return new KnowledgeBaseChunk(
                entity.getId(),
                entity.getSourceId(),
                entity.getChunkIndex(),
                entity.getContent(),
                entity.getEmbedding() == null ? null : Embedding.of(entity.getEmbedding()));
    }

    public static RetrievedChunk toRetrievedChunk(ChunkSimilarityRow row) {
        return new RetrievedChunk(
                row.getChunkId(),
                row.getSourceId(),
                row.getSourceTitle(),
                KnowledgeBaseSourceType.of(row.getSourceType()),
                row.getChunkIndex(),
                row.getContent(),
                row.getSimilarity());
    }

    /**
     * Converte o vetor para o literal aceito pelo pgvector: {@code [0.1,0.2,...]}.
     *
     * <p>Vai como texto, e não como array ligado ao driver, porque o parâmetro é convertido com
     * {@code CAST(... AS vector)} dentro da consulta nativa.
     */
    public static String toVectorLiteral(Embedding embedding) {
        StringJoiner literal = new StringJoiner(",", "[", "]");
        for (float value : embedding.toArray()) {
            literal.add(Float.toString(value));
        }
        return literal.toString();
    }
}
