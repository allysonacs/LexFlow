package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.infrastructure.persistence.entity.KnowledgeBaseChunkEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acesso à tabela {@code knowledge_base_chunks}.
 *
 * <p>A busca por similaridade de vetor não fica aqui: ela entra no Prompt 12, como consulta nativa
 * que usa o índice HNSW criado na migration V1.
 */
public interface KnowledgeBaseChunkJpaRepository extends JpaRepository<KnowledgeBaseChunkEntity, UUID> {

    List<KnowledgeBaseChunkEntity> findBySourceIdOrderByChunkIndexAsc(UUID sourceId);
}
