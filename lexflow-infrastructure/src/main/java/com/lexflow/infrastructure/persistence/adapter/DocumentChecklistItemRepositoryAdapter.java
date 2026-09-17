package com.lexflow.infrastructure.persistence.adapter;

import com.lexflow.application.checklist.DocumentChecklistItemRepository;
import com.lexflow.domain.checklist.DocumentChecklistItem;
import com.lexflow.infrastructure.persistence.mapper.DocumentChecklistItemMapper;
import com.lexflow.infrastructure.persistence.repository.DocumentChecklistItemJpaRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Implementação JPA da porta {@link DocumentChecklistItemRepository}. */
@Component
public class DocumentChecklistItemRepositoryAdapter implements DocumentChecklistItemRepository {

    private final DocumentChecklistItemJpaRepository jpaRepository;

    public DocumentChecklistItemRepositoryAdapter(DocumentChecklistItemJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<DocumentChecklistItem> findByLegalCaseId(UUID legalCaseId) {
        return jpaRepository.findByLegalCaseId(legalCaseId).stream()
                .map(DocumentChecklistItemMapper::toDomain)
                .toList();
    }

    @Override
    public void saveAll(List<DocumentChecklistItem> items) {
        jpaRepository.saveAll(items.stream().map(DocumentChecklistItemMapper::toEntity).toList());
    }

    @Override
    public boolean existsByChecklistRuleId(UUID checklistRuleId) {
        return jpaRepository.existsByChecklistRuleId(checklistRuleId);
    }
}
