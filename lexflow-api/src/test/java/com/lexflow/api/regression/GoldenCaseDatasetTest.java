package com.lexflow.api.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.domain.ai.AiAnalysisResponse;
import com.lexflow.domain.ai.ConfidenceScore;
import com.lexflow.domain.ai.QuestionKey;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A máquina do dataset de regressão, testada sem gastar uma chamada (Prompt 19).
 *
 * <p>Estes testes rodam na suíte normal de propósito: um carregador quebrado ou um avaliador que
 * aprova o que deveria reprovar só apareceriam durante uma execução paga — e tarde demais.
 */
class GoldenCaseDatasetTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("os casos declarados no índice são carregados e estão completos")
    void shouldLoadGoldenCases() {
        List<GoldenCase> cases = GoldenCases.loadAll(objectMapper);

        assertThat(cases).isNotEmpty();
        assertThat(cases).allSatisfy(goldenCase -> {
            assertThat(goldenCase.id()).isNotBlank();
            assertThat(goldenCase.caseType()).isNotBlank();
            assertThat(goldenCase.documents()).isNotEmpty();
            assertThat(goldenCase.expectedAnswers()).isNotEmpty();
            // Toda pergunta esperada precisa existir de verdade, e ser aplicável ao tipo da demanda.
            assertThat(goldenCase.expectedAnswers()).allSatisfy(expected -> assertThat(
                            QuestionKey.applicableTo(LegalCaseType.of(goldenCase.caseType())))
                    .contains(QuestionKey.valueOf(expected.questionKey())));
            // As normas que o caso indexa precisam existir ao lado dele.
            assertThat(goldenCase.knowledgeSources()).allSatisfy(source -> assertThat(
                            GoldenCases.readText(goldenCase.id(), source.file()))
                    .isNotBlank());
        });
    }

    @Test
    @DisplayName("uma resposta que atende ao gabarito é considerada correta")
    void shouldAcceptAnswerThatMeetsTheExpectations() {
        UUID chunk = UUID.randomUUID();
        GoldenCase goldenCase = goldenCaseWith(new GoldenCase.ExpectedAnswer(
                "CAN_SIGN_CONTRACT", "GROUNDED", List.of("diretor"), List.of("R$ 50.000,00"), "Política", 0.5, "VERIFIED"));

        List<GoldenCaseResult.QuestionResult> results = new GoldenCaseEvaluator(objectMapper)
                .evaluateAnswers(
                        goldenCase,
                        Map.of(
                                "CAN_SIGN_CONTRACT",
                                answer("Sim, com aprovação do diretor jurídico.", 0.8, List.of(chunk))
                                        .markVerified("Sustentada.")),
                        ids -> List.of("Política"));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.correct()).isTrue();
            assertThat(result.failures()).isEmpty();
        });
    }

    @Test
    @DisplayName("cada expectativa violada aparece nominalmente no resultado")
    void shouldReportEveryViolatedExpectation() {
        GoldenCase goldenCase = goldenCaseWith(new GoldenCase.ExpectedAnswer(
                "CAN_SIGN_CONTRACT",
                "GROUNDED",
                List.of("diretor"),
                List.of("R$ 50.000,00"),
                "Política de Alçadas",
                0.7,
                "VERIFIED"));

        List<GoldenCaseResult.QuestionResult> results = new GoldenCaseEvaluator(objectMapper)
                .evaluateAnswers(
                        goldenCase,
                        Map.of(
                                "CAN_SIGN_CONTRACT",
                                answer("Sim, o gerente pode assinar por R$ 50.000,00.", 0.4, List.of(UUID.randomUUID()))),
                        ids -> List.of("Outra Norma"));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.correct()).isFalse();
            assertThat(result.failures())
                    .anyMatch(failure -> failure.contains("não menciona \"diretor\""))
                    .anyMatch(failure -> failure.contains("R$ 50.000,00"))
                    .anyMatch(failure -> failure.contains("Política de Alçadas"))
                    .anyMatch(failure -> failure.contains("abaixo do mínimo"))
                    .anyMatch(failure -> failure.contains("segunda checagem"));
        });
    }

    @Test
    @DisplayName("uma pergunta não respondida é uma falha, não uma omissão silenciosa")
    void shouldFailWhenTheQuestionWasNotAnswered() {
        GoldenCase goldenCase = goldenCaseWith(new GoldenCase.ExpectedAnswer(
                "CAN_SIGN_CONTRACT", "GROUNDED", null, null, null, null, null));

        List<GoldenCaseResult.QuestionResult> results =
                new GoldenCaseEvaluator(objectMapper).evaluateAnswers(goldenCase, Map.of(), ids -> List.of());

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.correct()).isFalse();
            assertThat(result.failures()).containsExactly("pergunta não foi respondida");
        });
    }

    @Test
    @DisplayName("o gabarito de fatos é conferido como subconjunto: campo não declarado não é cobrado")
    void shouldCompareFactsAsSubset() {
        GoldenCaseEvaluator evaluator = new GoldenCaseEvaluator(objectMapper);
        String extracted =
                """
                {"documentKind":"Contrato","monetaryValues":[{"amountText":"R$ 15.000,00","amount":15000.0}],
                 "specificFacts":{"contractObject":"Prestação de serviços","penaltyClause":null}}
                """;

        GoldenCaseEvaluator.FactComparison certo = evaluator.compareFacts(
                Map.of("specificFacts", Map.of("contractObject", "Prestação de serviços")), extracted);
        GoldenCaseEvaluator.FactComparison errado = evaluator.compareFacts(
                Map.of("specificFacts", Map.of("contractObject", "Compra e venda")), extracted);

        assertThat(certo.checked()).isEqualTo(1);
        assertThat(certo.matched()).isEqualTo(1);
        assertThat(certo.differences()).isEmpty();
        assertThat(errado.matched()).isZero();
        assertThat(errado.differences())
                .singleElement()
                .satisfies(difference -> assertThat(difference).contains("specificFacts.contractObject"));
    }

    @Test
    @DisplayName("o relatório sai em JSON comparável e em Markdown legível")
    void shouldWriteAComparableReport() throws Exception {
        GoldenCaseResult result = new GoldenCaseResult(
                "caso-1",
                "CONTRACT_SIGNING",
                "COMPLETED",
                1234,
                3,
                2,
                1,
                2,
                2,
                List.of(),
                List.of(new GoldenCaseResult.QuestionResult(
                        "CAN_SIGN_CONTRACT", false, "LLM", "FAILED", 0.1, 1, List.of("confiança abaixo do mínimo"))));

        Path json = PromptRegressionReport.write(
                objectMapper, NOW, "claude-opus-5", Map.of("LEGAL_ANALYSIS", 1), List.of(result));

        assertThat(Files.readString(json))
                .contains("\"model\" : \"claude-opus-5\"")
                .contains("\"correctQuestionRate\"")
                .contains("\"promptVersions\"");
        assertThat(Files.readString(json.getParent().resolve("report.md")))
                .contains("# Regressão de prompts")
                .contains("caso-1")
                .contains("confiança abaixo do mínimo");
        // O resumo é o que se compara entre execuções.
        assertThat(PromptRegressionReport.summary(List.of(result)))
                .containsEntry("expectedQuestions", 3)
                .containsEntry("correctQuestions", 2)
                .containsEntry("failedVerifications", 1);
    }

    private static GoldenCase goldenCaseWith(GoldenCase.ExpectedAnswer expected) {
        return new GoldenCase(
                "caso", "descrição", "CONTRACT_SIGNING", "regressao", List.of(), List.of(), List.of(expected));
    }

    private static AiAnalysisResponse answer(String text, double confidence, List<UUID> citedChunks) {
        return AiAnalysisResponse.fromLlm(
                UUID.randomUUID(),
                UUID.randomUUID(),
                QuestionKey.CAN_SIGN_CONTRACT,
                text,
                ConfidenceScore.of(confidence),
                citedChunks,
                "claude-opus-5",
                UUID.randomUUID(),
                NOW);
    }
}
