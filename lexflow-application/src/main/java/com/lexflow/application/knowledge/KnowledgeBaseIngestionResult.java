package com.lexflow.application.knowledge;

import com.lexflow.domain.knowledge.KnowledgeBaseSource;
import java.util.Objects;

/**
 * Resultado da indexação de uma fonte normativa.
 *
 * @param chunkCount quantos trechos foram gerados e indexados
 * @param indexedCharacters total de caracteres indexados, somados os trechos
 */
public record KnowledgeBaseIngestionResult(KnowledgeBaseSource source, int chunkCount, int indexedCharacters) {

    public KnowledgeBaseIngestionResult {
        Objects.requireNonNull(source, "source não pode ser nulo");
    }
}
