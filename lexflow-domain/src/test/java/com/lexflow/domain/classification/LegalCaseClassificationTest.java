package com.lexflow.domain.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.domain.exception.DomainException;
import com.lexflow.domain.exception.LegalCaseTypeNotClassifiableException;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LegalCaseClassificationTest {

    private final LegalCaseKeywordClassifier classifier = new LegalCaseKeywordClassifier();

    @Test
    @DisplayName("tipo informado e confirmado pelas palavras-chave")
    void shouldConfirmDeclaredType() {
        LegalCaseClassification result =
                classifier.classify(LegalCaseType.CONTRACT_SIGNING, List.of("minuta.docx", "contrato.pdf"));

        assertThat(result.outcome()).isEqualTo(ClassificationOutcome.CONFIRMED);
        assertThat(result.resolvedType()).isEqualTo(LegalCaseType.CONTRACT_SIGNING);
        assertThat(result.declaredType()).isEqualTo(LegalCaseType.CONTRACT_SIGNING);
        assertThat(result.keywordSuggestion()).contains(LegalCaseType.CONTRACT_SIGNING);
        assertThat(result.outcome().requiresAttention()).isFalse();
        assertThat(result.summary())
                .contains("CONTRACT_SIGNING informado e confirmado")
                .contains("minuta")
                .contains("contrato");
    }

    @Test
    @DisplayName("empate que inclui o tipo informado conta como confirmação")
    void shouldConfirmDeclaredTypeWhenTiedAtTop() {
        LegalCaseClassification result =
                classifier.classify(LegalCaseType.PROPOSAL_ACCEPTANCE, List.of("contrato.pdf", "proposta.pdf"));

        assertThat(result.outcome()).isEqualTo(ClassificationOutcome.CONFIRMED);
        assertThat(result.keywordSuggestion()).isEmpty();
    }

    @Test
    @DisplayName("tipo informado sem nenhuma palavra-chave fica como não confirmado")
    void shouldKeepDeclaredTypeWithoutEvidence() {
        LegalCaseClassification result = classifier.classify(LegalCaseType.SUPPLIER_HIRING, List.of("scan001.pdf"));

        assertThat(result.outcome()).isEqualTo(ClassificationOutcome.UNCONFIRMED);
        assertThat(result.resolvedType()).isEqualTo(LegalCaseType.SUPPLIER_HIRING);
        assertThat(result.summary()).contains("nenhuma palavra-chave");
    }

    @Test
    @DisplayName("divergência é sinalizada, mas o tipo informado nunca é trocado")
    void shouldFlagDivergenceWithoutOverridingDeclaredType() {
        LegalCaseClassification result = classifier.classify(
                LegalCaseType.PROPOSAL_ACCEPTANCE, List.of("termo_de_acordo.pdf", "comprovante de pagamento.png"));

        assertThat(result.outcome()).isEqualTo(ClassificationOutcome.DIVERGENT);
        assertThat(result.outcome().requiresAttention()).isTrue();
        assertThat(result.resolvedType()).isEqualTo(LegalCaseType.PROPOSAL_ACCEPTANCE);
        assertThat(result.keywordSuggestion()).contains(LegalCaseType.SETTLEMENT_PAYMENT);
        assertThat(result.summary())
                .contains("PROPOSAL_ACCEPTANCE informado")
                .contains("SETTLEMENT_PAYMENT=2")
                .contains("revisar");
    }

    @Test
    @DisplayName("sem tipo informado, o tipo é deduzido quando não há empate")
    void shouldInferTypeWhenNotDeclared() {
        LegalCaseClassification result = classifier.classify(null, List.of("peticao.pdf"));

        assertThat(result.outcome()).isEqualTo(ClassificationOutcome.INFERRED);
        assertThat(result.declaredType()).isNull();
        assertThat(result.resolvedType()).isEqualTo(LegalCaseType.LAWSUIT_CLOSURE);
        assertThat(result.summary()).contains("LAWSUIT_CLOSURE deduzido");
    }

    @Test
    @DisplayName("sem tipo informado e sem evidência, a demanda não é classificada")
    void shouldRefuseToGuessWithoutEvidence() {
        assertThatExceptionOfType(LegalCaseTypeNotClassifiableException.class)
                .isThrownBy(() -> classifier.classify(null, List.of("arquivo.pdf")))
                .isInstanceOf(DomainException.class)
                .withMessageContaining("nenhuma palavra-chave")
                .satisfies(e -> assertThat(e.tiedTypes()).isEmpty());
    }

    @Test
    @DisplayName("sem tipo informado e com empate, a demanda não é classificada")
    void shouldRefuseToGuessOnTie() {
        assertThatExceptionOfType(LegalCaseTypeNotClassifiableException.class)
                .isThrownBy(() -> classifier.classify(null, List.of("contrato.pdf", "proposta.pdf")))
                .withMessageContaining("empate")
                .satisfies(e -> assertThat(e.tiedTypes())
                        .containsExactlyInAnyOrder(LegalCaseType.CONTRACT_SIGNING, LegalCaseType.PROPOSAL_ACCEPTANCE));
    }

    @Test
    @DisplayName("as invariantes impedem montar uma classificação incoerente")
    void shouldRejectInconsistentClassification() {
        KeywordClassification none = new KeywordClassification(List.of());

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new LegalCaseClassification(
                        LegalCaseType.CONTRACT_SIGNING, LegalCaseType.PROPOSAL_ACCEPTANCE,
                        ClassificationOutcome.DIVERGENT, none))
                .withMessageContaining("nunca é substituído");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new LegalCaseClassification(
                        null, LegalCaseType.CONTRACT_SIGNING, ClassificationOutcome.CONFIRMED, none));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new LegalCaseClassification(
                        LegalCaseType.CONTRACT_SIGNING, LegalCaseType.CONTRACT_SIGNING,
                        ClassificationOutcome.INFERRED, none));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new KeywordMatch(" ", LegalCaseType.CONTRACT_SIGNING));
    }
}
