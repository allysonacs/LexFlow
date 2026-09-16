package com.lexflow.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/** Mapeamento da tabela {@code knowledge_base_sources}: uma fonte normativa indexada para RAG. */
@Entity
@Table(name = "knowledge_base_sources")
public class KnowledgeBaseSourceEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "source_type", nullable = false, length = 50)
    private String sourceType;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    protected KnowledgeBaseSourceEntity() {
        // exigido pelo JPA
    }

    public KnowledgeBaseSourceEntity(UUID id, String title, String sourceType, LocalDate effectiveDate) {
        this.id = id;
        this.title = title;
        this.sourceType = sourceType;
        this.effectiveDate = effectiveDate;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getSourceType() {
        return sourceType;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }
}
