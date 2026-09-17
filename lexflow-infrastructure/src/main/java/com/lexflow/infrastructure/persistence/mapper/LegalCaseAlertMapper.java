package com.lexflow.infrastructure.persistence.mapper;

import com.lexflow.domain.alert.LegalCaseAlert;
import com.lexflow.infrastructure.persistence.entity.LegalCaseAlertEntity;

/** Converte entre {@link LegalCaseAlert} e {@link LegalCaseAlertEntity}. */
public final class LegalCaseAlertMapper {

    private LegalCaseAlertMapper() {
        // classe utilitária
    }

    public static LegalCaseAlertEntity toEntity(LegalCaseAlert alert) {
        return new LegalCaseAlertEntity(
                alert.id(),
                alert.legalCaseId(),
                alert.documentId(),
                alert.type(),
                alert.message(),
                alert.createdAt(),
                alert.resolvedAt());
    }

    public static LegalCaseAlert toDomain(LegalCaseAlertEntity entity) {
        return new LegalCaseAlert(
                entity.getId(),
                entity.getLegalCaseId(),
                entity.getDocumentId(),
                entity.getAlertType(),
                entity.getMessage(),
                entity.getCreatedAt(),
                entity.getResolvedAt());
    }
}
