package com.lexflow.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.lexflow.domain.legalcase.LegalCaseType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class QuestionKeyTest {

    @ParameterizedTest
    @EnumSource(LegalCaseType.class)
    @DisplayName("todo tipo de demanda responde à sua pergunta específica mais as duas comuns")
    void shouldCombineSpecificAndCommonQuestions(LegalCaseType caseType) {
        assertThat(QuestionKey.applicableTo(caseType))
                .containsExactly(
                        caseType.primaryQuestion(),
                        QuestionKey.HAS_SUFFICIENT_DOCUMENTATION,
                        QuestionKey.COMPLIES_WITH_LAW_AND_POLICY);
    }

    @Test
    void shouldMapEachCaseTypeToItsOwnQuestion() {
        assertThat(LegalCaseType.SUPPLIER_HIRING.primaryQuestion()).isEqualTo(QuestionKey.CAN_HIRE_SUPPLIER);
        assertThat(LegalCaseType.CONTRACT_SIGNING.primaryQuestion()).isEqualTo(QuestionKey.CAN_SIGN_CONTRACT);
        assertThat(LegalCaseType.SETTLEMENT_PAYMENT.primaryQuestion()).isEqualTo(QuestionKey.CAN_PAY_SETTLEMENT);
        assertThat(LegalCaseType.LAWSUIT_CLOSURE.primaryQuestion()).isEqualTo(QuestionKey.CAN_CLOSE_LAWSUIT);
        assertThat(LegalCaseType.PROPOSAL_ACCEPTANCE.primaryQuestion()).isEqualTo(QuestionKey.CAN_ACCEPT_PROPOSAL);
    }

    @Test
    @DisplayName("as perguntas críticas são exatamente as três da seção 10")
    void shouldFlagCriticalQuestions() {
        assertThat(QuestionKey.CAN_SIGN_CONTRACT.isCritical()).isTrue();
        assertThat(QuestionKey.CAN_PAY_SETTLEMENT.isCritical()).isTrue();
        assertThat(QuestionKey.CAN_CLOSE_LAWSUIT.isCritical()).isTrue();
        assertThat(QuestionKey.CAN_HIRE_SUPPLIER.isCritical()).isFalse();
        assertThat(QuestionKey.CAN_ACCEPT_PROPOSAL.isCritical()).isFalse();
        assertThat(QuestionKey.HAS_SUFFICIENT_DOCUMENTATION.isCritical()).isFalse();
        assertThat(QuestionKey.COMPLIES_WITH_LAW_AND_POLICY.isCritical()).isFalse();
    }

    @Test
    @DisplayName("só a suficiência documental é resolvida sem LLM")
    void shouldFlagDeterministicQuestion() {
        assertThat(QuestionKey.HAS_SUFFICIENT_DOCUMENTATION.isDeterministic()).isTrue();
        assertThat(QuestionKey.COMPLIES_WITH_LAW_AND_POLICY.isDeterministic()).isFalse();
    }

    @Test
    void applicableQuestionsShouldBeImmutable() {
        var questions = QuestionKey.applicableTo(LegalCaseType.CONTRACT_SIGNING);

        assertThat(questions).hasSize(3);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> questions.add(QuestionKey.CAN_HIRE_SUPPLIER))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
