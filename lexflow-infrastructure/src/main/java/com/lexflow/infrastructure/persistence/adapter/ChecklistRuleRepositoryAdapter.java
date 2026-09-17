package com.lexflow.infrastructure.persistence.adapter;

import com.lexflow.application.checklist.ChecklistRuleInUseException;
import com.lexflow.application.checklist.ChecklistRuleRepository;
import com.lexflow.application.checklist.DuplicateChecklistRuleException;
import com.lexflow.application.pagination.PageQuery;
import com.lexflow.application.pagination.PageResult;
import com.lexflow.domain.checklist.ChecklistRule;
import com.lexflow.domain.legalcase.LegalCaseType;
import com.lexflow.infrastructure.persistence.entity.ChecklistRuleEntity;
import com.lexflow.infrastructure.persistence.mapper.ChecklistRuleMapper;
import com.lexflow.infrastructure.persistence.repository.ChecklistRuleJpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Implementação JPA da porta {@link ChecklistRuleRepository}.
 *
 * <p>Gravação e exclusão forçam o {@code flush}: só assim a violação do índice único ou da chave
 * estrangeira aparece aqui, onde pode ser traduzida para uma exceção da aplicação, e não no commit.
 */
@Component
public class ChecklistRuleRepositoryAdapter implements ChecklistRuleRepository {

    private static final Sort DEFAULT_ORDER = Sort.by("caseType", "requiredDocumentType");

    private final ChecklistRuleJpaRepository jpaRepository;

    public ChecklistRuleRepositoryAdapter(ChecklistRuleJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public ChecklistRule save(ChecklistRule rule) {
        try {
            return ChecklistRuleMapper.toDomain(jpaRepository.saveAndFlush(ChecklistRuleMapper.toEntity(rule)));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateChecklistRuleException(rule.caseType(), rule.requiredDocumentType());
        }
    }

    @Override
    public Optional<ChecklistRule> findById(UUID id) {
        return jpaRepository.findById(id).map(ChecklistRuleMapper::toDomain);
    }

    @Override
    public List<ChecklistRule> findByCaseType(LegalCaseType caseType) {
        return jpaRepository.findByCaseTypeOrderByRequiredDocumentTypeAsc(caseType).stream()
                .map(ChecklistRuleMapper::toDomain)
                .toList();
    }

    @Override
    public List<ChecklistRule> findAllById(Collection<UUID> ids) {
        return jpaRepository.findAllById(ids).stream().map(ChecklistRuleMapper::toDomain).toList();
    }

    @Override
    public PageResult<ChecklistRule> findAll(LegalCaseType caseType, PageQuery pageQuery) {
        PageRequest pageRequest = PageRequest.of(pageQuery.page(), pageQuery.size(), DEFAULT_ORDER);
        Page<ChecklistRuleEntity> page = caseType == null
                ? jpaRepository.findAll(pageRequest)
                : jpaRepository.findByCaseType(caseType, pageRequest);
        return new PageResult<>(
                page.getContent().stream().map(ChecklistRuleMapper::toDomain).toList(),
                pageQuery.page(),
                pageQuery.size(),
                page.getTotalElements());
    }

    @Override
    public boolean existsByCaseTypeAndRequiredDocumentType(LegalCaseType caseType, String requiredDocumentType) {
        return jpaRepository.existsByCaseTypeAndRequiredDocumentType(caseType, requiredDocumentType);
    }

    @Override
    public void deleteById(UUID id) {
        try {
            jpaRepository.deleteById(id);
            jpaRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ChecklistRuleInUseException(id);
        }
    }
}
