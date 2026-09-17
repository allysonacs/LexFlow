package com.lexflow.infrastructure.persistence.adapter;

import com.lexflow.application.legalcase.LegalCaseRepository;
import com.lexflow.application.pagination.PageQuery;
import com.lexflow.application.pagination.PageResult;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.infrastructure.persistence.entity.LegalCaseEntity;
import com.lexflow.infrastructure.persistence.mapper.LegalCaseMapper;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/** Implementação JPA da porta {@link LegalCaseRepository}. */
@Component
public class LegalCaseRepositoryAdapter implements LegalCaseRepository {

    /** Ordem de chegada, com o identificador como desempate para a paginação ser estável. */
    private static final Sort DEFAULT_ORDER = Sort.by("createdAt", "id");

    private final LegalCaseJpaRepository jpaRepository;

    public LegalCaseRepositoryAdapter(LegalCaseJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public LegalCase save(LegalCase legalCase) {
        return LegalCaseMapper.toDomain(jpaRepository.save(LegalCaseMapper.toEntity(legalCase)));
    }

    @Override
    public Optional<LegalCase> findById(UUID id) {
        return jpaRepository.findById(id).map(LegalCaseMapper::toDomain);
    }

    @Override
    public PageResult<LegalCase> findAll(LegalCaseStatus status, PageQuery pageQuery) {
        PageRequest pageRequest = PageRequest.of(pageQuery.page(), pageQuery.size(), DEFAULT_ORDER);
        Page<LegalCaseEntity> page = status == null
                ? jpaRepository.findAll(pageRequest)
                : jpaRepository.findByStatus(status, pageRequest);
        return new PageResult<>(
                page.getContent().stream().map(LegalCaseMapper::toDomain).toList(),
                pageQuery.page(),
                pageQuery.size(),
                page.getTotalElements());
    }
}
