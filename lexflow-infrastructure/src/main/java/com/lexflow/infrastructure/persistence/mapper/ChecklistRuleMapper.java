package com.lexflow.infrastructure.persistence.mapper;

import com.lexflow.domain.checklist.ChecklistRule;
import com.lexflow.infrastructure.persistence.entity.ChecklistRuleEntity;

/** Converte entre {@link ChecklistRule} e {@link ChecklistRuleEntity}. */
public final class ChecklistRuleMapper {

    private ChecklistRuleMapper() {
        // classe utilitária
    }

    public static ChecklistRuleEntity toEntity(ChecklistRule rule) {
        return new ChecklistRuleEntity(
                rule.id(), rule.caseType(), rule.requiredDocumentType(), rule.description(), rule.mandatory());
    }

    public static ChecklistRule toDomain(ChecklistRuleEntity entity) {
        return new ChecklistRule(
                entity.getId(),
                entity.getCaseType(),
                entity.getRequiredDocumentType(),
                entity.getDescription(),
                entity.isMandatory());
    }
}
