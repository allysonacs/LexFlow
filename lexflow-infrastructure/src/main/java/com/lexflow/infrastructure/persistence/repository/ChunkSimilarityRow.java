package com.lexflow.infrastructure.persistence.repository;

import java.util.UUID;

/**
 * Projeção de uma linha da busca por similaridade.
 *
 * <p>Não é uma entidade: a consulta junta o trecho com a sua fonte e calcula a similaridade, e nada
 * disso precisa passar pelo contexto de persistência.
 */
public interface ChunkSimilarityRow {

    UUID getChunkId();

    UUID getSourceId();

    String getSourceTitle();

    String getSourceType();

    int getChunkIndex();

    String getContent();

    double getSimilarity();
}
