package com.lexflow.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mapeamento da tabela {@code prompt_versions}, que dá rastreabilidade ao prompt usado. */
@Entity
@Table(name = "prompt_versions")
public class PromptVersionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "prompt_key", nullable = false, length = 100)
    private String promptKey;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "template_text", nullable = false)
    private String templateText;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PromptVersionEntity() {
        // exigido pelo JPA
    }

    public PromptVersionEntity(
            UUID id, String promptKey, int version, String templateText, boolean active, Instant createdAt) {
        this.id = id;
        this.promptKey = promptKey;
        this.version = version;
        this.templateText = templateText;
        this.active = active;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getPromptKey() {
        return promptKey;
    }

    public int getVersion() {
        return version;
    }

    public String getTemplateText() {
        return templateText;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
