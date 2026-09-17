package com.lexflow.infrastructure.persistence.mapper;

import com.lexflow.domain.ai.PromptVersion;
import com.lexflow.infrastructure.persistence.entity.PromptVersionEntity;

/** Converte entre {@link PromptVersion} e {@link PromptVersionEntity}. */
public final class PromptVersionMapper {

    private PromptVersionMapper() {
        // classe utilitária
    }

    public static PromptVersionEntity toEntity(PromptVersion version) {
        return new PromptVersionEntity(
                version.id(),
                version.promptKey(),
                version.version(),
                version.templateText(),
                version.active(),
                version.createdAt());
    }

    public static PromptVersion toDomain(PromptVersionEntity entity) {
        return new PromptVersion(
                entity.getId(),
                entity.getPromptKey(),
                entity.getVersion(),
                entity.getTemplateText(),
                entity.isActive(),
                entity.getCreatedAt());
    }
}
