package com.lexflow.api.review;

import com.lexflow.domain.decision.Decision;
import java.time.Instant;
import java.util.UUID;

/** Decisão registrada, no contrato REST. */
public record DecisionResponse(
        UUID id,
        UUID legalCaseId,
        String decisionType,
        String resultingStatus,
        String decidedBy,
        Instant decidedAt,
        String comments) {

    public static DecisionResponse from(Decision decision) {
        return new DecisionResponse(
                decision.id(),
                decision.legalCaseId(),
                decision.decisionType().name(),
                decision.decisionType().resultingStatus().name(),
                decision.decidedBy(),
                decision.decidedAt(),
                decision.comments());
    }
}
