package com.lexflow.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Mapeamento da tabela {@code knowledge_base_chunks}.
 *
 * <p>O {@code embedding} usa o tipo {@code vector} do pgvector, suportado pelo Hibernate através do
 * módulo {@code hibernate-vector}. A dimensão precisa bater com a da migration; trocar de modelo de
 * embeddings exige nova migration e reindexação.
 */
@Entity
@Table(name = "knowledge_base_chunks")
public class KnowledgeBaseChunkEntity {

    /** Dimensão do vetor de embedding, igual à declarada na migration V1. */
    public static final int EMBEDDING_DIMENSION = 1536;

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "content", nullable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = EMBEDDING_DIMENSION)
    @Column(name = "embedding")
    private float[] embedding;

    protected KnowledgeBaseChunkEntity() {
        // exigido pelo JPA
    }

    public KnowledgeBaseChunkEntity(UUID id, UUID sourceId, int chunkIndex, String content, float[] embedding) {
        this.id = id;
        this.sourceId = sourceId;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.embedding = embedding;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public float[] getEmbedding() {
        return embedding;
    }
}
