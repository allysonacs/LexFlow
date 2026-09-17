package com.lexflow.domain.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.domain.legalcase.LegalCaseType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class LegalCaseKeywordClassifierTest {

    private final LegalCaseKeywordClassifier classifier = new LegalCaseKeywordClassifier();

    /**
     * Conjuntos de sinais realistas — nomes de arquivo como chegam do requisitante e descrições
     * livres —, um por tipo de demanda.
     */
    static Stream<Arguments> signalsByType() {
        return Stream.of(
                Arguments.of(LegalCaseType.SUPPLIER_HIRING, List.of(
                        "Cartao_CNPJ_Fornecedora_Modelo.pdf",
                        "certidao-negativa-debitos.pdf",
                        "Homologação de fornecedor de limpeza")),
                Arguments.of(LegalCaseType.CONTRACT_SIGNING, List.of(
                        "minuta_de_contrato_v3.docx",
                        "TERMO-ADITIVO-02.pdf",
                        "Revisar cláusulas contratuais antes da assinatura")),
                Arguments.of(LegalCaseType.SETTLEMENT_PAYMENT, List.of(
                        "termo_de_acordo_reclamacao_trabalhista.pdf",
                        "comprovante-de-pagamento.png",
                        "Pagamento de indenização acordada em audiência")),
                Arguments.of(LegalCaseType.LAWSUIT_CLOSURE, List.of(
                        "peticao_encerramento.pdf",
                        "sentenca-transito-em-julgado.pdf",
                        "Arquivamento do processo judicial após extinção")),
                Arguments.of(LegalCaseType.PROPOSAL_ACCEPTANCE, List.of(
                        "Proposta_Comercial_2026.pdf",
                        "orcamento-revisado.jpg",
                        "Avaliar a oferta recebida")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("signalsByType")
    @DisplayName("cada tipo de demanda é reconhecido pelos seus sinais")
    void shouldClassifyEachCaseType(LegalCaseType expected, List<String> signals) {
        KeywordClassification result = classifier.classify(signals);

        assertThat(result.suggestedType()).contains(expected);
        assertThat(result.topTypes()).containsExactly(expected);
        assertThat(result.keywordsFor(expected)).isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("signalsByType")
    @DisplayName("sem tipo informado, os sinais bastam para deduzir cada tipo")
    void shouldInferEachCaseTypeWhenNotDeclared(LegalCaseType expected, List<String> signals) {
        LegalCaseClassification classification = classifier.classify(null, signals);

        assertThat(classification.resolvedType()).isEqualTo(expected);
        assertThat(classification.outcome()).isEqualTo(ClassificationOutcome.INFERRED);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(LegalCaseType.class)
    @DisplayName("todo tipo do glossário tem palavras-chave na tabela padrão")
    void shouldCoverEveryCaseType(LegalCaseType type) {
        assertThat(LegalCaseKeywordClassifier.defaultKeywordTable().get(type)).isNotEmpty();
        assertThat(classifier.coveredTypes()).contains(type);
    }

    @Test
    @DisplayName("maiúsculas, acentos e separadores de nome de arquivo não atrapalham")
    void shouldNormalizeCaseAccentsAndSeparators() {
        assertThat(LegalCaseKeywordClassifier.tokenize("PETIÇÃO_de-Encerramento.v2.PDF"))
                .containsExactly("peticao", "de", "encerramento", "v2", "pdf");
        assertThat(classifier.classify(List.of("INDENIZAÇÃO.pdf")).suggestedType())
                .contains(LegalCaseType.SETTLEMENT_PAYMENT);
    }

    @Test
    @DisplayName("a expressão mais longa prevalece sobre a palavra solta")
    void shouldPreferLongestExpression() {
        KeywordClassification result = classifier.classify(List.of("acordo de confidencialidade.pdf"));

        assertThat(result.matches()).containsExactly(
                new KeywordMatch("acordo de confidencialidade", LegalCaseType.CONTRACT_SIGNING));
        assertThat(result.suggestedType()).contains(LegalCaseType.CONTRACT_SIGNING);
    }

    @Test
    @DisplayName("palavra-chave só casa com a palavra inteira, não com um pedaço dela")
    void shouldMatchWholeWordsOnly() {
        // "contratos" está na tabela, mas "contratoss" e "subcontrato" não devem casar.
        assertThat(classifier.classify(List.of("subcontrato contratoss")).hasEvidence()).isFalse();
    }

    @Test
    @DisplayName("cada ocorrência soma um ponto, e o tipo mais citado vence")
    void shouldScoreByOccurrences() {
        KeywordClassification result = classifier.classify(List.of("contrato.pdf", "contrato-assinado.pdf", "proposta.pdf"));

        assertThat(result.scores())
                .containsEntry(LegalCaseType.CONTRACT_SIGNING, 2)
                .containsEntry(LegalCaseType.PROPOSAL_ACCEPTANCE, 1);
        assertThat(result.suggestedType()).contains(LegalCaseType.CONTRACT_SIGNING);
        assertThat(result.keywordsFor(LegalCaseType.CONTRACT_SIGNING)).containsExactly("contrato");
    }

    @Test
    @DisplayName("empate não sugere tipo nenhum")
    void shouldNotSuggestOnTie() {
        KeywordClassification result = classifier.classify(List.of("contrato.pdf", "proposta.pdf"));

        assertThat(result.hasEvidence()).isTrue();
        assertThat(result.topTypes())
                .containsExactlyInAnyOrder(LegalCaseType.CONTRACT_SIGNING, LegalCaseType.PROPOSAL_ACCEPTANCE);
        assertThat(result.suggestedType()).isEmpty();
    }

    @Test
    @DisplayName("sinais sem palavra-chave, nulos ou vazios não produzem evidência")
    void shouldReturnNoEvidenceForIrrelevantSignals() {
        KeywordClassification result = classifier.classify(Arrays.asList("documento1.pdf", null, "  ", "scan.png"));

        assertThat(result.hasEvidence()).isFalse();
        assertThat(result.topTypes()).isEmpty();
        assertThat(result.scores()).isEmpty();
        assertThat(result.suggestedType()).isEmpty();
    }

    @Test
    @DisplayName("a mesma expressão não pode apontar para dois tipos")
    void shouldRejectAmbiguousTable() {
        Map<LegalCaseType, List<String>> table = Map.of(
                LegalCaseType.CONTRACT_SIGNING, List.of("acordo"),
                LegalCaseType.SETTLEMENT_PAYMENT, List.of("Acordo"));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new LegalCaseKeywordClassifier(table))
                .withMessageContaining("acordo");
    }

    @Test
    @DisplayName("expressão vazia na tabela é recusada")
    void shouldRejectBlankExpression() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new LegalCaseKeywordClassifier(Map.of(LegalCaseType.CONTRACT_SIGNING, List.of(" - "))));
    }

    @Test
    @DisplayName("uma tabela própria substitui a padrão")
    void shouldUseCustomTable() {
        LegalCaseKeywordClassifier custom =
                new LegalCaseKeywordClassifier(Map.of(LegalCaseType.LAWSUIT_CLOSURE, List.of("baixa definitiva")));

        assertThat(custom.classify(List.of("pedido de baixa definitiva")).suggestedType())
                .contains(LegalCaseType.LAWSUIT_CLOSURE);
        assertThat(custom.classify(List.of("contrato.pdf")).hasEvidence()).isFalse();
    }
}
