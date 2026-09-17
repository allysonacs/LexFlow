package com.lexflow.api.knowledge;

import com.lexflow.application.knowledge.RetrievedChunk;
import java.util.UUID;

/**
 * Trecho recuperado por similaridade, no contrato REST.
 *
 * <p>Traz o texto completo, e não só o identificador: é isso que permite conferir se a recuperação
 * está trazendo a norma certa antes de confiar nela na cadeia de prompts.
 */
public record RetrievedChunkResponse(
        UUID chunkId,
        UUID sourceId,
        String sourceTitle,
        String sourceType,
        int chunkIndex,
        String content,
        double similarity) {

    public static RetrievedChunkResponse from(RetrievedChunk chunk) {
        return new RetrievedChunkResponse(
                chunk.chunkId(),
                chunk.sourceId(),
                chunk.sourceTitle(),
                chunk.sourceType().name(),
                chunk.chunkIndex(),
                chunk.content(),
                chunk.similarity());
    }
}
