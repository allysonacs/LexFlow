package com.lexflow.infrastructure.persistence.adapter;

import com.lexflow.application.knowledge.KnowledgeBaseSourceRepository;
import com.lexflow.application.pagination.PageQuery;
import com.lexflow.application.pagination.PageResult;
import com.lexflow.domain.knowledge.KnowledgeBaseSource;
import com.lexflow.infrastructure.persistence.entity.KnowledgeBaseSourceEntity;
import com.lexflow.infrastructure.persistence.mapper.KnowledgeBaseMapper;
import com.lexflow.infrastructure.persistence.repository.KnowledgeBaseSourceJpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/** Implementação JPA da porta {@link KnowledgeBaseSourceRepository}. */
@Component
public class KnowledgeBaseSourceRepositoryAdapter implements KnowledgeBaseSourceRepository {

    private static final Sort DEFAULT_ORDER = Sort.by("title");

    private final KnowledgeBaseSourceJpaRepository jpaRepository;

    public KnowledgeBaseSourceRepositoryAdapter(KnowledgeBaseSourceJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public KnowledgeBaseSource save(KnowledgeBaseSource source) {
        return KnowledgeBaseMapper.toDomain(jpaRepository.save(KnowledgeBaseMapper.toEntity(source)));
    }

    @Override
    public Optional<KnowledgeBaseSource> findById(UUID id) {
        return jpaRepository.findById(id).map(KnowledgeBaseMapper::toDomain);
    }

    @Override
    public List<KnowledgeBaseSource> findAllById(Collection<UUID> ids) {
        return jpaRepository.findAllById(ids).stream().map(KnowledgeBaseMapper::toDomain).toList();
    }

    @Override
    public PageResult<KnowledgeBaseSource> findAll(PageQuery pageQuery) {
        Page<KnowledgeBaseSourceEntity> page =
                jpaRepository.findAll(PageRequest.of(pageQuery.page(), pageQuery.size(), DEFAULT_ORDER));
        return new PageResult<>(
                page.getContent().stream().map(KnowledgeBaseMapper::toDomain).toList(),
                pageQuery.page(),
                pageQuery.size(),
                page.getTotalElements());
    }
}
