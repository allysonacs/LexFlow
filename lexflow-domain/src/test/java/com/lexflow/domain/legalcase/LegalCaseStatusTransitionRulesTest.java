package com.lexflow.domain.legalcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.lexflow.domain.exception.InvalidStatusTransitionException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Cobre a matriz completa de transições: para cada um dos pares possíveis de status, o teste afirma
 * se a transição deve ser permitida ou recusada.
 *
 * <p>O mapa esperado é escrito aqui de forma independente da implementação, transcrito direto da
 * seção 4 da base de conhecimento. Assim, mudar as regras de produção sem mudar a base de
 * conhecimento quebra o teste.
 */
class LegalCaseStatusTransitionRulesTest {

    private final LegalCaseStatusTransition rules = new LegalCaseStatusTransitionRules();

    /** Transições permitidas segundo a base de conhecimento. */
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

    /** Todos os 81 pares (origem, destino) possíveis, com o resultado esperado. */
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
    void shouldAllowOnlyTheTransitionsDefinedInTheKnowledgeBase(
            LegalCaseStatus from, LegalCaseStatus to, boolean shouldBeAllowed) {
        assertThat(rules.isAllowed(from, to)).isEqualTo(shouldBeAllowed);
    }

    @ParameterizedTest(name = "{0} -> {1} permitida? {2}")
    @MethodSource("allStatusPairs")
    void validateShouldThrowExactlyForForbiddenTransitions(
            LegalCaseStatus from, LegalCaseStatus to, boolean shouldBeAllowed) {
        if (shouldBeAllowed) {
            rules.validateTransition(from, to);
            return;
        }
        assertThatExceptionOfType(InvalidStatusTransitionException.class)
                .isThrownBy(() -> rules.validateTransition(from, to))
                .satisfies(exception -> {
                    assertThat(exception.currentStatus()).isEqualTo(from);
                    assertThat(exception.targetStatus()).isEqualTo(to);
                    assertThat(exception.getMessage()).contains(from.name(), to.name());
                });
    }

    @Test
    @DisplayName("uma demanda devolvida para correção volta para RECEIVED após o reenvio")
    void shouldAllowReturnedForCorrectionBackToReceived() {
        assertThat(rules.isAllowed(LegalCaseStatus.RETURNED_FOR_CORRECTION, LegalCaseStatus.RECEIVED))
                .isTrue();
    }

    @Test
    @DisplayName("CLOSED é terminal: não sai nenhuma transição dele")
    void closedShouldBeTerminal() {
        assertThat(rules.allowedTransitionsFrom(LegalCaseStatus.CLOSED)).isEmpty();
        assertThat(LegalCaseStatus.CLOSED.isTerminal()).isTrue();
        assertThat(LegalCaseStatus.RECEIVED.isTerminal()).isFalse();
    }

    @Test
    void shouldRejectNullStatuses() {
        assertThatNullPointerException().isThrownBy(() -> rules.allowedTransitionsFrom(null));
        assertThatNullPointerException().isThrownBy(() -> rules.isAllowed(LegalCaseStatus.RECEIVED, null));
    }

    @Test
    @DisplayName("o conjunto devolvido não pode ser alterado por quem o recebe")
    void allowedTransitionsShouldBeImmutable() {
        Set<LegalCaseStatus> allowed = rules.allowedTransitionsFrom(LegalCaseStatus.RECEIVED);
        assertThat(allowed).containsExactly(LegalCaseStatus.CLASSIFYING);
        assertThat(allowed.getClass().getName()).doesNotContain("java.util.HashSet");
    }
}
