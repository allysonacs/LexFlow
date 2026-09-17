package com.lexflow.domain.knowledge;

import java.util.Objects;
import java.util.UUID;

/**
 * Trecho de uma fonte normativa, com o embedding usado na recuperação (seção 6).
 *
 * <p>O trecho é a unidade citada: é o seu identificador que aparece em {@code cited_chunks} e é o seu
 * texto que o revisor humano lê ao conferir uma resposta da IA (seção 10).
 *
 * @param chunkIndex posição do trecho dentro da fonte, começando em zero; é ela que permite ler os
 *     trechos na ordem original do documento
 * @param embedding vetor do trecho; nulo apenas enquanto a indexação não terminou
 */
public record KnowledgeBaseChunk(UUID id, UUID sourceId, int chunkIndex, String content, Embedding embedding) {

    public KnowledgeBaseChunk {
        Objects.requireNonNull(id, "id não pode ser nulo");
        Objects.requireNonNull(sourceId, "sourceId não pode ser nulo");
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex não pode ser negativo");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content é obrigatório: um trecho vazio não fundamenta nada");
        }
    }

    /** Indica se o trecho já pode ser recuperado por similaridade. */
    public boolean isIndexed() {
        return embedding != null;
    }

    /** Mesmo trecho, com o embedding calculado. */
    public KnowledgeBaseChunk withEmbedding(Embedding newEmbedding) {
        return new KnowledgeBaseChunk(
                id, sourceId, chunkIndex, content, Objects.requireNonNull(newEmbedding, "newEmbedding não pode ser nulo"));
    }

    /** O conteúdo é texto normativo público, mas o log não é lugar para despejar parágrafos inteiros. */
    @Override
    public String toString() {
        return "KnowledgeBaseChunk[id=%s, sourceId=%s, chunkIndex=%d, characters=%d, indexed=%s]"
                .formatted(id, sourceId, chunkIndex, content.length(), isIndexed());
    }
}
