package com.lexflow.domain.legalcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.lexflow.domain.exception.InvalidStatusTransitionException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LegalCaseTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-01-10T12:00:00Z");

    private LegalCase newCase() {
        return LegalCase.receive(
                UUID.randomUUID(),
                "REQ-2026-0001",
                LegalCaseType.CONTRACT_SIGNING,
                "compras@empresa.com",
                CasePriority.NORMAL,
                RECEIVED_AT);
    }

    @Test
    void shouldStartAsReceived() {
        LegalCase legalCase = newCase();

        assertThat(legalCase.status()).isEqualTo(LegalCaseStatus.RECEIVED);
        assertThat(legalCase.createdAt()).isEqualTo(RECEIVED_AT);
        assertThat(legalCase.updatedAt()).isEqualTo(RECEIVED_AT);
        assertThat(legalCase.isClosed()).isFalse();
        assertThat(legalCase.caseType().primaryQuestion().name()).isEqualTo("CAN_SIGN_CONTRACT");
    }

    @Test
    @DisplayName("a transição devolve uma nova instância e preserva a original")
    void shouldReturnNewInstanceOnTransition() {
        LegalCase received = newCase();
        Instant classifiedAt = RECEIVED_AT.plus(1, ChronoUnit.MINUTES);

        LegalCase classifying = received.transitionTo(LegalCaseStatus.CLASSIFYING, classifiedAt);

        assertThat(classifying).isNotSameAs(received);
        assertThat(classifying.status()).isEqualTo(LegalCaseStatus.CLASSIFYING);
        assertThat(classifying.updatedAt()).isEqualTo(classifiedAt);
        assertThat(classifying.createdAt()).isEqualTo(RECEIVED_AT);
        assertThat(classifying.id()).isEqualTo(received.id());
        assertThat(received.status()).isEqualTo(LegalCaseStatus.RECEIVED);
        assertThat(received.updatedAt()).isEqualTo(RECEIVED_AT);
    }

    @Test
    @DisplayName("o caminho feliz completo, da entrada ao encerramento")
    void shouldWalkThroughTheWholeHappyPath() {
        LegalCase legalCase = newCase();
        Instant now = RECEIVED_AT;

        for (LegalCaseStatus next : new LegalCaseStatus[] {
            LegalCaseStatus.CLASSIFYING,
            LegalCaseStatus.EXTRACTING,
            LegalCaseStatus.AI_ANALYSIS_IN_PROGRESS,
            LegalCaseStatus.PENDING_HUMAN_REVIEW,
            LegalCaseStatus.APPROVED,
            LegalCaseStatus.CLOSED
        }) {
            now = now.plus(1, ChronoUnit.MINUTES);
            legalCase = legalCase.transitionTo(next, now);
        }

        assertThat(legalCase.status()).isEqualTo(LegalCaseStatus.CLOSED);
        assertThat(legalCase.isClosed()).isTrue();
        assertThat(legalCase.allowedNextStatuses()).isEmpty();
    }

    @Test
    @DisplayName("uma demanda devolvida para correção volta para RECEIVED")
    void shouldReopenAfterReturnedForCorrection() {
        LegalCase pendingReview = newCase()
                .transitionTo(LegalCaseStatus.CLASSIFYING, RECEIVED_AT)
                .transitionTo(LegalCaseStatus.EXTRACTING, RECEIVED_AT)
                .transitionTo(LegalCaseStatus.AI_ANALYSIS_IN_PROGRESS, RECEIVED_AT)
                .transitionTo(LegalCaseStatus.PENDING_HUMAN_REVIEW, RECEIVED_AT);

        LegalCase returned = pendingReview.transitionTo(LegalCaseStatus.RETURNED_FOR_CORRECTION, RECEIVED_AT);
        LegalCase reopened = returned.transitionTo(LegalCaseStatus.RECEIVED, RECEIVED_AT);

        assertThat(reopened.status()).isEqualTo(LegalCaseStatus.RECEIVED);
    }

    @Test
    void shouldRejectForbiddenTransition() {
        LegalCase received = newCase();

        assertThat(received.canTransitionTo(LegalCaseStatus.APPROVED)).isFalse();
        assertThatExceptionOfType(InvalidStatusTransitionException.class)
                .isThrownBy(() -> received.transitionTo(LegalCaseStatus.APPROVED, RECEIVED_AT));
    }

    @Test
    @DisplayName("aceita uma máquina de estados informada explicitamente")
    void shouldAcceptCustomTransitionRules() {
        LegalCase received = newCase();
        LegalCaseStatusTransition permissiveRules = currentStatus -> java.util.Set.of(LegalCaseStatus.CLOSED);

        LegalCase closed = received.transitionTo(LegalCaseStatus.CLOSED, RECEIVED_AT, permissiveRules);

        assertThat(closed.status()).isEqualTo(LegalCaseStatus.CLOSED);
        assertThat(received.canTransitionTo(LegalCaseStatus.CLOSED)).isFalse();
    }

    @Test
    void shouldValidateRequiredFields() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LegalCase.receive(
                        UUID.randomUUID(),
                        "REQ-1",
                        LegalCaseType.SUPPLIER_HIRING,
                        "  ",
                        CasePriority.LOW,
                        RECEIVED_AT))
                .withMessageContaining("requester");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LegalCase.receive(
                        UUID.randomUUID(),
                        "",
                        LegalCaseType.SUPPLIER_HIRING,
                        "analista",
                        CasePriority.LOW,
                        RECEIVED_AT))
                .withMessageContaining("externalReference");

        assertThatNullPointerException()
                .isThrownBy(() -> LegalCase.receive(
                        UUID.randomUUID(), "REQ-1", null, "analista", CasePriority.LOW, RECEIVED_AT));

        assertThatNullPointerException().isThrownBy(() -> newCase().transitionTo(LegalCaseStatus.CLASSIFYING, null));
    }

    @Test
    @DisplayName("externalReference é opcional")
    void shouldAcceptNullExternalReference() {
        LegalCase legalCase = LegalCase.receive(
                UUID.randomUUID(),
                null,
                LegalCaseType.LAWSUIT_CLOSURE,
                "juridico@empresa.com",
                CasePriority.URGENT,
                RECEIVED_AT);

        assertThat(legalCase.externalReference()).isNull();
    }

    @Test
    @DisplayName("a descrição é opcional, preservada nas transições e limitada em tamanho")
    void shouldHandleDescription() {
        LegalCase withDescription = LegalCase.receive(
                UUID.randomUUID(),
                null,
                LegalCaseType.CONTRACT_SIGNING,
                "analista",
                "Minuta de contrato de licenciamento",
                CasePriority.NORMAL,
                RECEIVED_AT);

        assertThat(withDescription.description()).isEqualTo("Minuta de contrato de licenciamento");
        assertThat(withDescription.transitionTo(LegalCaseStatus.CLASSIFYING, RECEIVED_AT).description())
                .isEqualTo("Minuta de contrato de licenciamento");
        assertThat(newCase().description()).isNull();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LegalCase.receive(
                        UUID.randomUUID(), null, LegalCaseType.CONTRACT_SIGNING, "analista", " ",
                        CasePriority.NORMAL, RECEIVED_AT))
                .withMessageContaining("description");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LegalCase.receive(
                        UUID.randomUUID(), null, LegalCaseType.CONTRACT_SIGNING, "analista",
                        "x".repeat(LegalCase.DESCRIPTION_MAX_LENGTH + 1), CasePriority.NORMAL, RECEIVED_AT))
                .withMessageContaining(String.valueOf(LegalCase.DESCRIPTION_MAX_LENGTH));
    }
}
