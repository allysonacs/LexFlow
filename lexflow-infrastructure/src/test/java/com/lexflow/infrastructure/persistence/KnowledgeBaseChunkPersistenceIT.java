package com.lexflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.lexflow.infrastructure.persistence.entity.KnowledgeBaseChunkEntity;
import com.lexflow.infrastructure.persistence.entity.KnowledgeBaseSourceEntity;
import com.lexflow.infrastructure.persistence.repository.KnowledgeBaseChunkJpaRepository;
import com.lexflow.infrastructure.persistence.repository.KnowledgeBaseSourceJpaRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Confirma que a extensão pgvector está instalada pela migration e que um embedding vai e volta do
 * banco sem alteração. A busca por similaridade em si é assunto do Prompt 12.
 */
class KnowledgeBaseChunkPersistenceIT extends AbstractPersistenceIT {

    @Autowired
    private KnowledgeBaseSourceJpaRepository sourceRepository;

    @Autowired
    private KnowledgeBaseChunkJpaRepository chunkRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("um trecho normativo com embedding é gravado na coluna vector e recuperado igual")
    void shouldPersistChunkWithEmbedding() {
        KnowledgeBaseSourceEntity source = sourceRepository.save(new KnowledgeBaseSourceEntity(
                UUID.randomUUID(), "Política de Alçadas 2026", "INTERNAL_POLICY", LocalDate.of(2026, 1, 1)));

        float[] embedding = new float[KnowledgeBaseChunkEntity.EMBEDDING_DIMENSION];
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] = i / 1000.0f;
        }
        KnowledgeBaseChunkEntity chunk = chunkRepository.save(new KnowledgeBaseChunkEntity(
                UUID.randomUUID(),
                source.getId(),
                0,
                "A assinatura de contratos acima de R$ 100.000,00 depende de aprovação do diretor.",
                embedding));

        entityManager.flush();
        entityManager.clear();

        KnowledgeBaseChunkEntity reloaded =
                chunkRepository.findById(chunk.getId()).orElseThrow();

        assertThat(reloaded.getContent()).contains("aprovação do diretor");
        assertThat(reloaded.getChunkIndex()).isZero();
        assertThat(reloaded.getEmbedding()).hasSize(KnowledgeBaseChunkEntity.EMBEDDING_DIMENSION);
        assertThat(reloaded.getEmbedding()).containsExactly(embedding);
        assertThat(chunkRepository.findBySourceIdOrderByChunkIndexAsc(source.getId()))
                .hasSize(1);
    }

    @Test
    @DisplayName("a extensão vector e o índice HNSW existem no banco")
    void shouldHaveVectorExtensionAndIndex() {
        Object extension = entityManager
                .createNativeQuery("SELECT extname FROM pg_extension WHERE extname = 'vector'")
                .getSingleResult();
        Object index = entityManager
                .createNativeQuery("SELECT indexname FROM pg_indexes WHERE indexname = :name")
                .setParameter("name", "idx_knowledge_base_chunks_embedding")
                .getSingleResult();

        assertThat(extension).isEqualTo("vector");
        assertThat(index).isEqualTo("idx_knowledge_base_chunks_embedding");
    }
}
