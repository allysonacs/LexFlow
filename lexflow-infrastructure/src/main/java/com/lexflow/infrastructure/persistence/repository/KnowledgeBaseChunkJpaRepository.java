package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.infrastructure.persistence.entity.KnowledgeBaseChunkEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Acesso à tabela {@code knowledge_base_chunks}, incluindo a busca vetorial do RAG (Prompt 12).
 *
 * <p>A busca por similaridade é uma consulta nativa porque JPQL não conhece o operador {@code <=>} do
 * pgvector — que é justamente o que faz o PostgreSQL usar o índice HNSW criado na migration V1. O
 * vetor da consulta vai como texto e é convertido com {@code CAST(... AS vector)}: a sintaxe
 * {@code ::vector} seria confundida com um parâmetro nomeado pelo Hibernate.
 *
 * <p>{@code 1 - distância} converte a distância de cosseno em similaridade, que é o número mais
 * intuitivo para quem lê o resultado: 1.0 é idêntico, 0.0 é sem relação.
 */
public interface KnowledgeBaseChunkJpaRepository extends JpaRepository<KnowledgeBaseChunkEntity, UUID> {

    List<KnowledgeBaseChunkEntity> findBySourceIdOrderByChunkIndexAsc(UUID sourceId);

    long countBySourceId(UUID sourceId);

    @Modifying
    @Query(value = "DELETE FROM knowledge_base_chunks WHERE source_id = :sourceId", nativeQuery = true)
    void deleteBySourceId(@Param("sourceId") UUID sourceId);

    /** Os {@code limit} trechos mais próximos da consulta, do mais próximo ao mais distante. */
    @Query(
            value =
                    """
                    SELECT c.id          AS "chunkId",
                           c.source_id   AS "sourceId",
                           s.title       AS "sourceTitle",
                           s.source_type AS "sourceType",
                           c.chunk_index AS "chunkIndex",
                           c.content     AS "content",
                           1 - (c.embedding <=> CAST(:embedding AS vector)) AS "similarity"
                    FROM knowledge_base_chunks c
                    JOIN knowledge_base_sources s ON s.id = c.source_id
                    WHERE c.embedding IS NOT NULL
                    ORDER BY c.embedding <=> CAST(:embedding AS vector)
                    LIMIT :limit
                    """,
            nativeQuery = true)
    List<ChunkSimilarityRow> findMostSimilar(@Param("embedding") String embedding, @Param("limit") int limit);

    /** Trechos citados por uma resposta já gravada, com a fonte, sem cálculo de similaridade. */
    @Query(
            value =
                    """
                    SELECT c.id          AS "chunkId",
                           c.source_id   AS "sourceId",
                           s.title       AS "sourceTitle",
                           s.source_type AS "sourceType",
                           c.chunk_index AS "chunkIndex",
                           c.content     AS "content",
                           0.0           AS "similarity"
                    FROM knowledge_base_chunks c
                    JOIN knowledge_base_sources s ON s.id = c.source_id
                    WHERE c.id IN (:chunkIds)
                    """,
            nativeQuery = true)
    List<ChunkSimilarityRow> findCited(@Param("chunkIds") Collection<UUID> chunkIds);
}
