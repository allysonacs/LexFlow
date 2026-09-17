package com.lexflow.domain.knowledge;

/**
 * Trecho de texto produzido pela divisão de uma fonte, antes de receber identificador e embedding.
 *
 * @param index posição do trecho na fonte, começando em zero
 */
public record TextChunk(int index, String content) {

    public TextChunk {
        if (index < 0) {
            throw new IllegalArgumentException("index não pode ser negativo");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content é obrigatório");
        }
    }
}
