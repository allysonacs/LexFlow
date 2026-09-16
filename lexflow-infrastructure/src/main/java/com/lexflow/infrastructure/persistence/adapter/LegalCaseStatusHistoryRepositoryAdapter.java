package com.lexflow.infrastructure.persistence.adapter;

import com.lexflow.application.legalcase.LegalCaseStatusHistoryEntry;
import com.lexflow.application.legalcase.LegalCaseStatusHistoryRepository;
import com.lexflow.infrastructure.persistence.mapper.LegalCaseStatusHistoryMapper;
import com.lexflow.infrastructure.persistence.repository.LegalCaseStatusHistoryJpaRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implementação JPA da porta {@link LegalCaseStatusHistoryRepository}.
 *
 * <p>Só acrescenta linhas: o histórico é somente-acréscimo, então não há aqui nenhuma operação de
 * alteração ou remoção.
 */
@Component
public class LegalCaseStatusHistoryRepositoryAdapter implements LegalCaseStatusHistoryRepository {

    private final LegalCaseStatusHistoryJpaRepository jpaRepository;

    public LegalCaseStatusHistoryRepositoryAdapter(LegalCaseStatusHistoryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(LegalCaseStatusHistoryEntry entry) {
        jpaRepository.save(LegalCaseStatusHistoryMapper.toEntity(entry));
    }

    @Override
    public List<LegalCaseStatusHistoryEntry> findByLegalCaseId(UUID legalCaseId) {
        return jpaRepository.findByLegalCaseIdOrderByChangedAtAsc(legalCaseId).stream()
                .map(LegalCaseStatusHistoryMapper::toDomain)
                .toList();
    }
}
