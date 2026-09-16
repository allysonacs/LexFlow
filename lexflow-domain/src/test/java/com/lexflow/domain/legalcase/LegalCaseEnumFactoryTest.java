package com.lexflow.domain.legalcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.domain.exception.UnknownCasePriorityException;
import com.lexflow.domain.exception.UnknownLegalCaseTypeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Cobre a conversão de texto vindo de um cliente externo para os enums do domínio: é o que sustenta
 * a resposta 400 do endpoint de ingestão quando o tipo de demanda é inválido.
 */
class LegalCaseEnumFactoryTest {

    @Test
    @DisplayName("aceita o nome do tipo, tolerando espaços e caixa diferente")
    void shouldParseLegalCaseType() {
        assertThat(LegalCaseType.of("CONTRACT_SIGNING")).isEqualTo(LegalCaseType.CONTRACT_SIGNING);
        assertThat(LegalCaseType.of("  contract_signing  ")).isEqualTo(LegalCaseType.CONTRACT_SIGNING);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"CONTRATO", "CONTRACT-SIGNING", "UNKNOWN_TYPE"})
    @DisplayName("um tipo fora do glossário é recusado, com a lista de valores aceitos na mensagem")
    void shouldRejectUnknownLegalCaseType(String value) {
        assertThatExceptionOfType(UnknownLegalCaseTypeException.class)
                .isThrownBy(() -> LegalCaseType.of(value))
                .withMessageContaining("SUPPLIER_HIRING");
    }

    @Test
    void shouldExposeSupportedLegalCaseTypes() {
        assertThat(LegalCaseType.supportedValues())
                .containsExactly(
                        "SUPPLIER_HIRING",
                        "CONTRACT_SIGNING",
                        "SETTLEMENT_PAYMENT",
                        "LAWSUIT_CLOSURE",
                        "PROPOSAL_ACCEPTANCE");
    }

    @Test
    void shouldParseCasePriority() {
        assertThat(CasePriority.of("urgent")).isEqualTo(CasePriority.URGENT);
        assertThat(CasePriority.DEFAULT).isEqualTo(CasePriority.NORMAL);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ALTA", "VERY_HIGH"})
    void shouldRejectUnknownCasePriority(String value) {
        assertThatExceptionOfType(UnknownCasePriorityException.class)
                .isThrownBy(() -> CasePriority.of(value))
                .withMessageContaining("NORMAL");
    }

    @Test
    void shouldExposeSupportedPriorities() {
        assertThat(CasePriority.supportedValues()).containsExactly("LOW", "NORMAL", "HIGH", "URGENT");
    }
}
