package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.infrastructure.persistence.entity.PromptVersionEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso à tabela {@code prompt_versions}. */
public interface PromptVersionJpaRepository extends JpaRepository<PromptVersionEntity, UUID> {

    Optional<PromptVersionEntity> findByPromptKeyAndActiveIsTrue(String promptKey);
}
