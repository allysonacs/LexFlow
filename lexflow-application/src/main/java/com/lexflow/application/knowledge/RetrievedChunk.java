package com.lexflow.application.knowledge;

import com.lexflow.domain.knowledge.KnowledgeBaseSourceType;
import java.util.Objects;
import java.util.UUID;

/**
 * Trecho normativo recuperado por similaridade, com a fonte de onde veio.
 *
 * <p>Carrega o título e o tipo da fonte porque é assim que ele será citado: o revisor humano precisa
 * ver de onde saiu o fundamento, não só um identificador (seção 10).
 *
 * @param similarity similaridade de cosseno com a consulta, de -1.0 a 1.0; quanto maior, mais próximo
 */
public record RetrievedChunk(
        UUID chunkId,
        UUID sourceId,
        String sourceTitle,
        KnowledgeBaseSourceType sourceType,
        int chunkIndex,
        String content,
        double similarity) {

    public RetrievedChunk {
        Objects.requireNonNull(chunkId, "chunkId não pode ser nulo");
        Objects.requireNonNull(sourceId, "sourceId não pode ser nulo");
        Objects.requireNonNull(content, "content não pode ser nulo");
    }

    /** Referência curta da fonte, para compor o prompt e a citação: {@code Lei 14.133/2021 #3}. */
    public String citation() {
        return "%s #%d".formatted(sourceTitle, chunkIndex);
    }
}
