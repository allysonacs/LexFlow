package com.lexflow.application.knowledge;

import com.lexflow.domain.knowledge.Embedding;
import com.lexflow.domain.knowledge.KnowledgeBaseChunk;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Porta de saída dos trechos normativos, incluindo a busca por similaridade.
 *
 * <p>A busca fica no repositório, e não em código Java, porque quem sabe percorrer o índice vetorial
 * é o banco: carregar a base inteira para calcular distâncias na aplicação não escalaria.
 */
public interface KnowledgeBaseChunkRepository {

    /** Grava os trechos de uma fonte. */
    void saveAll(List<KnowledgeBaseChunk> chunks);

    /** Remove os trechos de uma fonte, para que uma reindexação não deixe trechos antigos na base. */
    void deleteBySourceId(UUID sourceId);

    long countBySourceId(UUID sourceId);

    /** Trechos de uma fonte, na ordem do documento. */
    List<KnowledgeBaseChunk> findBySourceId(UUID sourceId);

    /**
     * Trechos citados, para exibir o texto da fonte ao revisor humano (Prompt 15).
     *
     * <p>Identificadores inexistentes são ignorados: um trecho pode ter sido removido por uma
     * reindexação depois de a resposta ter sido gerada.
     */
    List<RetrievedChunk> findAllById(Collection<UUID> chunkIds);

    /**
     * Os trechos mais próximos da consulta, por distância de cosseno, usando o índice vetorial.
     *
     * @param limit quantidade máxima de trechos devolvidos, do mais próximo ao mais distante
     */
    List<RetrievedChunk> findMostSimilar(Embedding queryEmbedding, int limit);
}
