package com.lexflow.infrastructure.persistence.adapter;

import com.lexflow.application.document.DocumentTextContentRepository;
import com.lexflow.domain.document.DocumentTextContent;
import com.lexflow.infrastructure.persistence.mapper.DocumentTextContentMapper;
import com.lexflow.infrastructure.persistence.repository.DocumentTextContentJpaRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Implementação JPA da porta {@link DocumentTextContentRepository}. */
@Component
public class DocumentTextContentRepositoryAdapter implements DocumentTextContentRepository {

    private final DocumentTextContentJpaRepository jpaRepository;

    public DocumentTextContentRepositoryAdapter(DocumentTextContentJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(DocumentTextContent textContent) {
        jpaRepository.save(DocumentTextContentMapper.toEntity(textContent));
    }

    @Override
    public List<DocumentTextContent> findByLegalCaseId(UUID legalCaseId) {
        return jpaRepository.findByLegalCaseId(legalCaseId).stream()
                .map(DocumentTextContentMapper::toDomain)
                .toList();
    }
}
