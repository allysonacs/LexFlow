package com.lexflow.infrastructure.persistence.mapper;

import com.lexflow.domain.decision.Decision;
import com.lexflow.infrastructure.persistence.entity.DecisionEntity;

/** Converte entre {@link Decision} e {@link DecisionEntity}. */
public final class DecisionMapper {

    private DecisionMapper() {
        // classe utilitária
    }

    public static DecisionEntity toEntity(Decision decision) {
        return new DecisionEntity(
                decision.id(),
                decision.legalCaseId(),
                decision.decisionType(),
                decision.decidedBy(),
                decision.decidedAt(),
                decision.comments());
    }

    public static Decision toDomain(DecisionEntity entity) {
        return new Decision(
                entity.getId(),
                entity.getLegalCaseId(),
                entity.getDecisionType(),
                entity.getDecidedBy(),
                entity.getDecidedAt(),
                entity.getComments());
    }
}
