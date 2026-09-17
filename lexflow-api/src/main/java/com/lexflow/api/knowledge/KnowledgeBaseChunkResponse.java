package com.lexflow.api.knowledge;

import com.lexflow.domain.knowledge.KnowledgeBaseChunk;
import java.util.UUID;

/**
 * Trecho normativo no contrato REST.
 *
 * <p>O embedding não é devolvido: são centenas de números que não dizem nada a quem lê a norma.
 *
 * @param indexed indica se o trecho já pode ser recuperado por similaridade
 */
public record KnowledgeBaseChunkResponse(UUID id, int chunkIndex, String content, boolean indexed) {

    public static KnowledgeBaseChunkResponse from(KnowledgeBaseChunk chunk) {
        return new KnowledgeBaseChunkResponse(chunk.id(), chunk.chunkIndex(), chunk.content(), chunk.isIndexed());
    }
}
