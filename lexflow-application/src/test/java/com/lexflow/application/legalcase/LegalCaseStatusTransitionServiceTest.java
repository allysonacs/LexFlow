package com.lexflow.application.legalcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.lexflow.domain.exception.InvalidStatusTransitionException;
import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseStatusTransition;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Cobre a matriz completa de transições através do serviço, e não só das regras do domínio: o que
 * está em teste aqui é que o ponto único de mudança de status recusa o que a seção 4 proíbe e produz
 * o registro de histórico do que ela permite.
 */
class LegalCaseStatusTransitionServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-05T14:30:00Z");
    private static final UUID HISTORY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final LegalCaseStatusTransitionService service =
            new LegalCaseStatusTransitionService(new com.lexflow.domain.legalcase.LegalCaseStatusTransitionRules(),
                    fixedClock,
                    () -> HISTORY_ID);

    /** Transições permitidas segundo a seção 4, transcritas de forma independente da implementação. */
    private static Map<LegalCaseStatus, Set<LegalCaseStatus>> expectedTransitions() {
        Map<LegalCaseStatus, Set<LegalCaseStatus>> expected = new EnumMap<>(LegalCaseStatus.class);
        expected.put(LegalCaseStatus.RECEIVED, Set.of(LegalCaseStatus.CLASSIFYING));
        expected.put(LegalCaseStatus.CLASSIFYING, Set.of(LegalCaseStatus.EXTRACTING));
        expected.put(LegalCaseStatus.EXTRACTING, Set.of(LegalCaseStatus.AI_ANALYSIS_IN_PROGRESS));
        expected.put(LegalCaseStatus.AI_ANALYSIS_IN_PROGRESS, Set.of(LegalCaseStatus.PENDING_HUMAN_REVIEW));
        expected.put(
                LegalCaseStatus.PENDING_HUMAN_REVIEW,
                Set.of(
                        LegalCaseStatus.APPROVED,
                        LegalCaseStatus.REJECTED,
                        LegalCaseStatus.RETURNED_FOR_CORRECTION));
        expected.put(LegalCaseStatus.APPROVED, Set.of(LegalCaseStatus.CLOSED));
        expected.put(LegalCaseStatus.REJECTED, Set.of(LegalCaseStatus.CLOSED));
        expected.put(LegalCaseStatus.RETURNED_FOR_CORRECTION, Set.of(LegalCaseStatus.RECEIVED));
        expected.put(LegalCaseStatus.CLOSED, Set.of());
        return expected;
    }

    static Stream<Arguments> allStatusPairs() {
        Map<LegalCaseStatus, Set<LegalCaseStatus>> expected = expectedTransitions();
        List<Arguments> pairs = new ArrayList<>();
        for (LegalCaseStatus from : LegalCaseStatus.values()) {
            for (LegalCaseStatus to : LegalCaseStatus.values()) {
                pairs.add(Arguments.of(from, to, expected.get(from).contains(to)));
            }
        }
        return pairs.stream();
    }

    @ParameterizedTest(name = "{0} -> {1} permitida? {2}")
    @MethodSource("allStatusPairs")
    @DisplayName("o serviço aceita exatamente as transições da base de conhecimento")
    void shouldTransitionOnlyWhenAllowed(LegalCaseStatus from, LegalCaseStatus to, boolean shouldBeAllowed) {
        LegalCase legalCase = caseWithStatus(from);

        if (!shouldBeAllowed) {
            assertThat(service.canTransition(legalCase, to)).isFalse();
            assertThatExceptionOfType(InvalidStatusTransitionException.class)
                    .isThrownBy(() -> service.transition(legalCase, to, "SYSTEM", null))
                    .satisfies(exception -> {
                        assertThat(exception.currentStatus()).isEqualTo(from);
                        assertThat(exception.targetStatus()).isEqualTo(to);
                    });
            return;
        }

        LegalCaseStatusTransitionResult result = service.transition(legalCase, to, "SYSTEM", "motivo");

        assertThat(service.canTransition(legalCase, to)).isTrue();
        assertThat(result.legalCase().status()).isEqualTo(to);
        assertThat(result.historyEntry().previousStatus()).isEqualTo(from);
        assertThat(result.historyEntry().newStatus()).isEqualTo(to);
        assertThat(result.historyEntry().legalCaseId()).isEqualTo(legalCase.id());
        assertThat(result.historyEntry().isInitial()).isFalse();
    }

    @Test
    @DisplayName("a devolução para correção e a reabertura da demanda")
    void shouldSupportReturnForCorrectionAndReopening() {
        LegalCase pendingReview = caseWithStatus(LegalCaseStatus.PENDING_HUMAN_REVIEW);

        LegalCaseStatusTransitionResult returned = service.transition(
                pendingReview,
                LegalCaseStatus.RETURNED_FOR_CORRECTION,
                "revisor@empresa.com",
                "Falta o parecer financeiro.");
        LegalCaseStatusTransitionResult reopened = service.transition(
                returned.legalCase(), LegalCaseStatus.RECEIVED, "SYSTEM", "Nova documentação enviada");

        assertThat(returned.legalCase().status()).isEqualTo(LegalCaseStatus.RETURNED_FOR_CORRECTION);
        assertThat(returned.historyEntry().changedBy()).isEqualTo("revisor@empresa.com");
        assertThat(returned.historyEntry().reason()).isEqualTo("Falta o parecer financeiro.");
        assertThat(reopened.legalCase().status()).isEqualTo(LegalCaseStatus.RECEIVED);
        assertThat(reopened.historyEntry().previousStatus()).isEqualTo(LegalCaseStatus.RETURNED_FOR_CORRECTION);
    }

    @Test
    @DisplayName("a demanda original não é alterada: o serviço devolve uma nova instância")
    void shouldNotMutateTheOriginalCase() {
        LegalCase received = caseWithStatus(LegalCaseStatus.RECEIVED);

        LegalCaseStatusTransitionResult result =
                service.transition(received, LegalCaseStatus.CLASSIFYING, "SYSTEM", null);

        assertThat(received.status()).isEqualTo(LegalCaseStatus.RECEIVED);
        assertThat(received.updatedAt()).isNotEqualTo(NOW);
        assertThat(result.legalCase()).isNotSameAs(received);
    }

    @Test
    @DisplayName("o horário do registro e o da demanda vêm do mesmo relógio")
    void shouldUseTheClockForBothCaseAndHistory() {
        LegalCaseStatusTransitionResult result =
                service.transition(caseWithStatus(LegalCaseStatus.RECEIVED), LegalCaseStatus.CLASSIFYING, "SYSTEM", null);

        assertThat(result.historyEntry().changedAt()).isEqualTo(NOW);
        assertThat(result.legalCase().updatedAt()).isEqualTo(NOW);
        assertThat(result.historyEntry().id()).isEqualTo(HISTORY_ID);
    }

    @Test
    @DisplayName("o registro inicial abre o histórico com previousStatus nulo")
    void shouldRegisterInitialStatus() {
        LegalCase received = caseWithStatus(LegalCaseStatus.RECEIVED);

        LegalCaseStatusTransitionResult result =
                service.registerInitialStatus(received, "ingestao-api", "Demanda recebida via API");

        assertThat(result.legalCase()).isSameAs(received);
        assertThat(result.historyEntry().isInitial()).isTrue();
        assertThat(result.historyEntry().previousStatus()).isNull();
        assertThat(result.historyEntry().newStatus()).isEqualTo(LegalCaseStatus.RECEIVED);
        assertThat(result.historyEntry().changedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("o registro inicial só vale para uma demanda em RECEIVED")
    void shouldRejectInitialRegistrationForOtherStatuses() {
        LegalCase classifying = caseWithStatus(LegalCaseStatus.CLASSIFYING);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.registerInitialStatus(classifying, "SYSTEM", null))
                .withMessageContaining("RECEIVED");
    }

    @Test
    @DisplayName("toda transição precisa de um autor identificado")
    void shouldRequireChangedBy() {
        LegalCase received = caseWithStatus(LegalCaseStatus.RECEIVED);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.transition(received, LegalCaseStatus.CLASSIFYING, "  ", null))
                .withMessageContaining("changedBy");
    }

    @Test
    void shouldExposeAllowedNextStatuses() {
        assertThat(service.allowedNextStatuses(caseWithStatus(LegalCaseStatus.PENDING_HUMAN_REVIEW)))
                .containsExactlyInAnyOrder(
                        LegalCaseStatus.APPROVED,
                        LegalCaseStatus.REJECTED,
                        LegalCaseStatus.RETURNED_FOR_CORRECTION);
        assertThat(service.allowedNextStatuses(caseWithStatus(LegalCaseStatus.CLOSED)))
                .isEmpty();
    }

    @Test
    @DisplayName("aceita uma máquina de estados informada, para o caso de a regra evoluir")
    void shouldAcceptCustomTransitionRules() {
        LegalCaseStatusTransition permissiveRules = currentStatus -> Set.of(LegalCaseStatus.CLOSED);
        LegalCaseStatusTransitionService permissiveService =
                new LegalCaseStatusTransitionService(permissiveRules, fixedClock, () -> HISTORY_ID);
        LegalCase received = caseWithStatus(LegalCaseStatus.RECEIVED);

        assertThat(permissiveService.transition(received, LegalCaseStatus.CLOSED, "SYSTEM", null)
                        .legalCase()
                        .status())
                .isEqualTo(LegalCaseStatus.CLOSED);
        assertThat(service.canTransition(received, LegalCaseStatus.CLOSED)).isFalse();
    }

    @Test
    void shouldRejectNullArguments() {
        LegalCase received = caseWithStatus(LegalCaseStatus.RECEIVED);

        assertThatNullPointerException()
                .isThrownBy(() -> service.transition(null, LegalCaseStatus.CLASSIFYING, "SYSTEM", null));
        assertThatNullPointerException().isThrownBy(() -> service.transition(received, null, "SYSTEM", null));
        assertThatNullPointerException().isThrownBy(() -> service.canTransition(null, LegalCaseStatus.CLASSIFYING));
        assertThatNullPointerException().isThrownBy(() -> service.allowedNextStatuses(null));
        assertThatNullPointerException().isThrownBy(() -> service.registerInitialStatus(null, "SYSTEM", null));
        assertThatNullPointerException()
                .isThrownBy(() -> new LegalCaseStatusTransitionService(null, fixedClock, () -> HISTORY_ID));
        assertThatNullPointerException()
                .isThrownBy(() -> new LegalCaseStatusTransitionService(
                        new com.lexflow.domain.legalcase.LegalCaseStatusTransitionRules(), null, () -> HISTORY_ID));
    }

    @Test
    @DisplayName("a configuração padrão usa as regras da base de conhecimento e o relógio do sistema")
    void defaultConstructorShouldUseKnowledgeBaseRules() {
        LegalCaseStatusTransitionService defaultService = new LegalCaseStatusTransitionService();
        LegalCase received = caseWithStatus(LegalCaseStatus.RECEIVED);

        LegalCaseStatusTransitionResult result =
                defaultService.transition(received, LegalCaseStatus.CLASSIFYING, "SYSTEM", null);

        assertThat(result.legalCase().status()).isEqualTo(LegalCaseStatus.CLASSIFYING);
        assertThat(result.historyEntry().changedAt()).isAfter(NOW);
        assertThat(defaultService.canTransition(received, LegalCaseStatus.APPROVED)).isFalse();
    }

    /** Monta uma demanda já no status desejado, sem passar pelo serviço. */
    private LegalCase caseWithStatus(LegalCaseStatus status) {
        return new LegalCase(
                UUID.randomUUID(),
                "REQ-2026-0100",
                LegalCaseType.CONTRACT_SIGNING,
                status,
                "compras@empresa.com",
                null,
                CasePriority.NORMAL,
                Instant.parse("2026-03-01T09:00:00Z"),
                Instant.parse("2026-03-01T09:00:00Z"));
    }
}
