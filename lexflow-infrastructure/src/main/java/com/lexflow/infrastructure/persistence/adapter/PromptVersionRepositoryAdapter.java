package com.lexflow.infrastructure.persistence.adapter;

import com.lexflow.application.prompt.PromptVersionRepository;
import com.lexflow.domain.ai.PromptVersion;
import com.lexflow.infrastructure.persistence.mapper.PromptVersionMapper;
import com.lexflow.infrastructure.persistence.repository.PromptVersionJpaRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Implementação JPA da porta {@link PromptVersionRepository}. */
@Component
public class PromptVersionRepositoryAdapter implements PromptVersionRepository {

    private final PromptVersionJpaRepository jpaRepository;

    public PromptVersionRepositoryAdapter(PromptVersionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<PromptVersion> findActive(String promptKey) {
        return jpaRepository.findByPromptKeyAndActiveIsTrue(promptKey).map(PromptVersionMapper::toDomain);
    }
}
