package com.lexflow.api.review;

import com.lexflow.application.knowledge.RetrievedChunk;
import java.util.UUID;

/**
 * Trecho normativo citado por uma resposta, com o **texto completo**.
 *
 * <p>O texto vai junto de propósito: o revisor humano precisa ler a fonte, não apenas o identificador
 * dela (seção 10). Sem isso, a interface só poderia mostrar a conclusão da IA.
 */
public record CitedChunkResponse(
        UUID chunkId, UUID sourceId, String sourceTitle, String sourceType, int chunkIndex, String content) {

    public static CitedChunkResponse from(RetrievedChunk chunk) {
        return new CitedChunkResponse(
                chunk.chunkId(),
                chunk.sourceId(),
                chunk.sourceTitle(),
                chunk.sourceType().name(),
                chunk.chunkIndex(),
                chunk.content());
    }
}
