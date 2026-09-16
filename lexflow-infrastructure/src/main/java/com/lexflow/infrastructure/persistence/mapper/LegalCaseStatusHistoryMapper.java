package com.lexflow.infrastructure.persistence.mapper;

import com.lexflow.application.legalcase.LegalCaseStatusHistoryEntry;
import com.lexflow.infrastructure.persistence.entity.LegalCaseStatusHistoryEntity;

/**
 * Converte entre {@link LegalCaseStatusHistoryEntry} e {@link LegalCaseStatusHistoryEntity}.
 *
 * <p>O registro de histórico é definido na camada de aplicação, e não no domínio, porque descreve o
 * rastro de uma transição e não uma regra jurídica — mas o motivo de existir um mapper é o mesmo dos
 * demais: manter as anotações de persistência fora das camadas de dentro.
 */
public final class LegalCaseStatusHistoryMapper {

    private LegalCaseStatusHistoryMapper() {
        // classe utilitária
    }

    public static LegalCaseStatusHistoryEntity toEntity(LegalCaseStatusHistoryEntry entry) {
        return new LegalCaseStatusHistoryEntity(
                entry.id(),
                entry.legalCaseId(),
                entry.previousStatus(),
                entry.newStatus(),
                entry.changedAt(),
                entry.changedBy(),
                entry.reason());
    }

    public static LegalCaseStatusHistoryEntry toDomain(LegalCaseStatusHistoryEntity entity) {
        return new LegalCaseStatusHistoryEntry(
                entity.getId(),
                entity.getLegalCaseId(),
                entity.getPreviousStatus(),
                entity.getNewStatus(),
                entity.getChangedAt(),
                entity.getChangedBy(),
                entity.getReason());
    }
}
