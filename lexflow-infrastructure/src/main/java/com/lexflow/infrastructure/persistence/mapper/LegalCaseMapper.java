package com.lexflow.infrastructure.persistence.mapper;

import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.infrastructure.persistence.entity.LegalCaseEntity;

/**
 * Converte entre o agregado de domínio {@link LegalCase} e a entidade JPA {@link LegalCaseEntity}.
 *
 * <p>Existe para manter o módulo {@code lexflow-domain} livre de anotações de persistência: o domínio
 * não sabe que é gravado em banco.
 */
public final class LegalCaseMapper {

    private LegalCaseMapper() {
        // classe utilitária
    }

    public static LegalCaseEntity toEntity(LegalCase legalCase) {
        return new LegalCaseEntity(
                legalCase.id(),
                legalCase.externalReference(),
                legalCase.caseType(),
                legalCase.status(),
                legalCase.requester(),
                legalCase.description(),
                legalCase.priority(),
                legalCase.createdAt(),
                legalCase.updatedAt());
    }

    public static LegalCase toDomain(LegalCaseEntity entity) {
        return new LegalCase(
                entity.getId(),
                entity.getExternalReference(),
                entity.getCaseType(),
                entity.getStatus(),
                entity.getRequester(),
                entity.getDescription(),
                entity.getPriority(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
