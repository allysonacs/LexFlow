package com.lexflow.infrastructure.persistence.adapter;

import com.lexflow.application.knowledge.KnowledgeBaseChunkRepository;
import com.lexflow.application.knowledge.RetrievedChunk;
import com.lexflow.domain.knowledge.Embedding;
import com.lexflow.domain.knowledge.KnowledgeBaseChunk;
import com.lexflow.infrastructure.persistence.entity.KnowledgeBaseChunkEntity;
import com.lexflow.infrastructure.persistence.mapper.KnowledgeBaseMapper;
import com.lexflow.infrastructure.persistence.repository.KnowledgeBaseChunkJpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implementação JPA da porta {@link KnowledgeBaseChunkRepository}, incluindo a busca vetorial.
 *
 * <p>A dimensão de cada embedding é conferida antes da gravação: um vetor de outro tamanho seria
 * recusado pelo PostgreSQL com uma mensagem do driver, e é melhor falhar aqui, dizendo que o modelo
 * de embeddings não corresponde ao schema.
 */
@Component
public class KnowledgeBaseChunkRepositoryAdapter implements KnowledgeBaseChunkRepository {

    private final KnowledgeBaseChunkJpaRepository jpaRepository;

    public KnowledgeBaseChunkRepositoryAdapter(KnowledgeBaseChunkJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void saveAll(List<KnowledgeBaseChunk> chunks) {
        List<KnowledgeBaseChunkEntity> entities = chunks.stream()
                .peek(chunk -> {
                    if (chunk.isIndexed()) {
                        chunk.embedding().requireDimension(KnowledgeBaseChunkEntity.EMBEDDING_DIMENSION);
                    }
                })
                .map(KnowledgeBaseMapper::toEntity)
                .toList();
        jpaRepository.saveAll(entities);
    }

    @Override
    public void deleteBySourceId(UUID sourceId) {
        jpaRepository.deleteBySourceId(sourceId);
    }

    @Override
    public long countBySourceId(UUID sourceId) {
        return jpaRepository.countBySourceId(sourceId);
    }

    @Override
    public List<KnowledgeBaseChunk> findBySourceId(UUID sourceId) {
        return jpaRepository.findBySourceIdOrderByChunkIndexAsc(sourceId).stream()
                .map(KnowledgeBaseMapper::toDomain)
                .toList();
    }

    @Override
    public List<RetrievedChunk> findAllById(Collection<UUID> chunkIds) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        return jpaRepository.findCited(chunkIds).stream()
                .map(KnowledgeBaseMapper::toRetrievedChunk)
                .toList();
    }

    @Override
    public List<RetrievedChunk> findMostSimilar(Embedding queryEmbedding, int limit) {
        queryEmbedding.requireDimension(KnowledgeBaseChunkEntity.EMBEDDING_DIMENSION);
        return jpaRepository
                .findMostSimilar(KnowledgeBaseMapper.toVectorLiteral(queryEmbedding), limit)
                .stream()
                .map(KnowledgeBaseMapper::toRetrievedChunk)
                .toList();
    }
}
