package com.lexflow.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.lexflow.domain.legalcase.LegalCaseStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DecisionTest {

    private static final Instant DECIDED_AT = Instant.parse("2026-01-10T12:00:00Z");

    @Test
    void shouldRegisterDecision() {
        Decision decision = new Decision(
                UUID.randomUUID(),
                UUID.randomUUID(),
                DecisionType.APPROVED,
                "revisor@empresa.com",
                DECIDED_AT,
                "Dentro da alçada.");

        assertThat(decision.decisionType()).isEqualTo(DecisionType.APPROVED);
        assertThat(decision.decidedBy()).isEqualTo("revisor@empresa.com");
    }

    @Test
    @DisplayName("toda decisão precisa de um responsável humano identificado")
    void shouldRequireDecidedBy() {
        UUID id = UUID.randomUUID();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Decision(id, id, DecisionType.REJECTED, "  ", DECIDED_AT, "motivo"))
                .withMessageContaining("decidedBy");

        assertThatNullPointerException()
                .isThrownBy(() -> new Decision(id, id, null, "revisor", DECIDED_AT, "motivo"));
    }

    @Test
    @DisplayName("devolver para correção exige explicar o motivo ao requisitante")
    void shouldRequireCommentsWhenReturningForCorrection() {
        UUID id = UUID.randomUUID();

        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        new Decision(id, id, DecisionType.RETURNED_FOR_CORRECTION, "revisor", DECIDED_AT, null))
                .withMessageContaining("comentários");

        Decision valid = new Decision(
                id, id, DecisionType.RETURNED_FOR_CORRECTION, "revisor", DECIDED_AT, "Falta o parecer financeiro.");
        assertThat(valid.comments()).isNotBlank();
    }

    @Test
    @DisplayName("cada tipo de decisão leva a demanda ao status correspondente")
    void shouldMapDecisionTypeToResultingStatus() {
        assertThat(DecisionType.APPROVED.resultingStatus()).isEqualTo(LegalCaseStatus.APPROVED);
        assertThat(DecisionType.REJECTED.resultingStatus()).isEqualTo(LegalCaseStatus.REJECTED);
        assertThat(DecisionType.RETURNED_FOR_CORRECTION.resultingStatus())
                .isEqualTo(LegalCaseStatus.RETURNED_FOR_CORRECTION);
    }

    @Test
    @DisplayName("todo status resultante de uma decisão é alcançável a partir de PENDING_HUMAN_REVIEW")
    void everyDecisionShouldBeReachableFromPendingHumanReview() {
        var rules = new com.lexflow.domain.legalcase.LegalCaseStatusTransitionRules();

        for (DecisionType decisionType : DecisionType.values()) {
            assertThat(rules.isAllowed(LegalCaseStatus.PENDING_HUMAN_REVIEW, decisionType.resultingStatus()))
                    .as("decisão %s", decisionType)
                    .isTrue();
        }
    }
}
