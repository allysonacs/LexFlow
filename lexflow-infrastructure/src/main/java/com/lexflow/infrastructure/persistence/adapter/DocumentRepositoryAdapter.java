package com.lexflow.infrastructure.persistence.adapter;

import com.lexflow.application.document.DocumentRepository;
import com.lexflow.domain.document.Document;
import com.lexflow.infrastructure.persistence.entity.DocumentEntity;
import com.lexflow.infrastructure.persistence.mapper.DocumentMapper;
import com.lexflow.infrastructure.persistence.repository.DocumentJpaRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Implementação JPA da porta {@link DocumentRepository}. */
@Component
public class DocumentRepositoryAdapter implements DocumentRepository {

    private final DocumentJpaRepository jpaRepository;

    public DocumentRepositoryAdapter(DocumentJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void saveAll(List<Document> documents) {
        List<DocumentEntity> entities =
                documents.stream().map(DocumentMapper::toEntity).toList();
        jpaRepository.saveAll(entities);
    }

    @Override
    public List<Document> findByLegalCaseId(UUID legalCaseId) {
        return jpaRepository.findByLegalCaseIdOrderByUploadedAtAscFileNameAscIdAsc(legalCaseId).stream()
                .map(DocumentMapper::toDomain)
                .toList();
    }
}
