package com.lexflow.application.knowledge;

import com.lexflow.application.pagination.PageQuery;
import com.lexflow.application.pagination.PageResult;
import com.lexflow.domain.knowledge.KnowledgeBaseSource;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de saída das fontes normativas. */
public interface KnowledgeBaseSourceRepository {

    KnowledgeBaseSource save(KnowledgeBaseSource source);

    Optional<KnowledgeBaseSource> findById(UUID id);

    List<KnowledgeBaseSource> findAllById(Collection<UUID> ids);

    /** Listagem paginada, ordenada por título (seção 11: toda listagem é paginada). */
    PageResult<KnowledgeBaseSource> findAll(PageQuery pageQuery);
}
