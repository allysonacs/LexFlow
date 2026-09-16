package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.infrastructure.persistence.entity.KnowledgeBaseSourceEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso à tabela {@code knowledge_base_sources}. */
public interface KnowledgeBaseSourceJpaRepository extends JpaRepository<KnowledgeBaseSourceEntity, UUID> {}
