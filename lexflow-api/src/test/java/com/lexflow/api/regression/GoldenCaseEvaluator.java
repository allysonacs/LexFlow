package com.lexflow.api.regression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.api.regression.GoldenCase.ExpectedAnswer;
import com.lexflow.domain.ai.AiAnalysisResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Confere o que a IA respondeu contra o gabarito de um caso dourado (Prompt 19, item 3).
 *
 * <p>A conferência é por propriedades, e não por texto: o que se exige de uma resposta é que ela
 * esteja fundamentada, cite a norma certa, mencione o que importa, não mencione o que não existe e
 * não desabe de confiança. Comparar o texto gerado com um texto esperado reprovaria qualquer
 * variação de redação e aprovaria uma resposta bem escrita com a conclusão errada.
 */
public class GoldenCaseEvaluator {

    private final ObjectMapper objectMapper;

    public GoldenCaseEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Avalia as respostas de um caso.
     *
     * @param responses respostas gravadas, por {@code question_key}
     * @param citedSourceTitles dado um conjunto de trechos citados, os títulos das fontes deles
     */
    public List<GoldenCaseResult.QuestionResult> evaluateAnswers(
            GoldenCase goldenCase,
            Map<String, AiAnalysisResponse> responses,
            Function<List<UUID>, List<String>> citedSourceTitles) {

        List<GoldenCaseResult.QuestionResult> results = new ArrayList<>();
        for (ExpectedAnswer expected : goldenCase.expectedAnswers()) {
            AiAnalysisResponse response = responses.get(expected.questionKey());
            if (response == null) {
                results.add(new GoldenCaseResult.QuestionResult(
                        expected.questionKey(), false, null, null, 0.0, 0, List.of("pergunta não foi respondida")));
                continue;
            }

            List<String> failures = new ArrayList<>();
            String answer = response.answerText().toLowerCase(Locale.ROOT);

            switch (expected.stance() == null ? "GROUNDED" : expected.stance()) {
                case "GROUNDED" -> {
                    if (response.citedChunks().isEmpty()) {
                        failures.add("esperava resposta fundamentada, mas nenhum trecho foi citado");
                    }
                    if (response.declaresNotFound()) {
                        failures.add("esperava resposta fundamentada, mas ela declara que nada foi encontrado");
                    }
                }
                case "NOT_FOUND" -> {
                    if (!response.declaresNotFound()) {
                        failures.add("esperava a declaração de que a base normativa não tem o assunto");
                    }
                }
                case "DETERMINISTIC" -> {
                    if (response.isFromLlm()) {
                        failures.add("esperava resposta determinística, mas ela veio do modelo");
                    }
                }
                default -> failures.add("stance desconhecido no gabarito: " + expected.stance());
            }

            expected.mustMentionOrEmpty().forEach(term -> {
                if (!answer.contains(term.toLowerCase(Locale.ROOT))) {
                    failures.add("a resposta não menciona \"%s\"".formatted(term));
                }
            });
            expected.mustNotMentionOrEmpty().forEach(term -> {
                if (answer.contains(term.toLowerCase(Locale.ROOT))) {
                    failures.add("a resposta menciona \"%s\", que não está nos documentos nem nas normas".formatted(term));
                }
            });

            if (expected.mustCiteSource() != null) {
                List<String> titles = citedSourceTitles.apply(response.citedChunks());
                if (titles.stream().noneMatch(title -> title.equalsIgnoreCase(expected.mustCiteSource()))) {
                    failures.add("esperava citação de \"%s\", mas foram citadas %s"
                            .formatted(expected.mustCiteSource(), titles));
                }
            }
            if (expected.minConfidence() != null && response.confidenceScore().value() < expected.minConfidence()) {
                failures.add("confiança %.2f abaixo do mínimo %.2f"
                        .formatted(response.confidenceScore().value(), expected.minConfidence()));
            }
            if (expected.expectedVerification() != null
                    && !expected.expectedVerification().equals(response.verificationStatus().name())) {
                failures.add("segunda checagem em %s, esperava %s"
                        .formatted(response.verificationStatus(), expected.expectedVerification()));
            }

            results.add(new GoldenCaseResult.QuestionResult(
                    expected.questionKey(),
                    failures.isEmpty(),
                    response.answerSource().name(),
                    response.verificationStatus().name(),
                    response.confidenceScore().value(),
                    response.citedChunks().size(),
                    List.copyOf(failures)));
        }
        return List.copyOf(results);
    }

    /**
     * Confere o gabarito parcial de fatos de um documento.
     *
     * @return um par: quantos campos foram conferidos e quantos bateram, mais as divergências
     */
    public FactComparison compareFacts(Map<String, Object> expectedFacts, String extractedJson) {
        if (expectedFacts == null || expectedFacts.isEmpty()) {
            return new FactComparison(0, 0, List.of());
        }
        JsonNode extracted;
        try {
            extracted = objectMapper.readTree(extractedJson);
        } catch (Exception e) {
            return new FactComparison(expectedFacts.size(), 0, List.of("fatos extraídos não são JSON válido"));
        }
        JsonNode expected = objectMapper.valueToTree(expectedFacts);

        List<String> differences = new ArrayList<>();
        int[] counters = new int[2];
        compare("", expected, extracted, counters, differences);
        return new FactComparison(counters[0], counters[1], List.copyOf(differences));
    }

    /**
     * Compara o gabarito como subconjunto do extraído: campo não declarado não é conferido.
     *
     * <p>É o que permite um gabarito curto — só o que realmente importa naquele documento — sem
     * transformar cada campo novo do schema em uma quebra do dataset.
     */
    private void compare(String path, JsonNode expected, JsonNode actual, int[] counters, List<String> differences) {
        if (expected.isObject()) {
            expected.properties().forEach(entry -> compare(
                    path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(),
                    entry.getValue(),
                    actual == null ? null : actual.path(entry.getKey()),
                    counters,
                    differences));
            return;
        }
        if (expected.isArray()) {
            for (int index = 0; index < expected.size(); index++) {
                compare(
                        "%s[%d]".formatted(path, index),
                        expected.get(index),
                        actual != null && actual.isArray() && index < actual.size() ? actual.get(index) : null,
                        counters,
                        differences);
            }
            return;
        }

        counters[0]++;
        if (actual != null && !actual.isMissingNode() && expected.asText().equalsIgnoreCase(actual.asText())) {
            counters[1]++;
            return;
        }
        differences.add("%s: esperava \"%s\", veio \"%s\""
                .formatted(path, expected.asText(), actual == null || actual.isMissingNode() ? "ausente" : actual.asText()));
    }

    /** Resultado da comparação de fatos de um documento. */
    public record FactComparison(int checked, int matched, List<String> differences) {}
}
