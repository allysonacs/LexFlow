package com.lexflow.api.regression;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relatório de uma execução do dataset de regressão (Prompt 19, item 4).
 *
 * <p>São dois arquivos, com propósitos diferentes: o JSON é para comparar execuções — o formato é
 * estável, e um `diff` entre dois arquivos mostra exatamente o que mudou —, e o Markdown é para ler.
 *
 * <p>O JSON registra o modelo e as versões de prompt usadas. Sem isso, dois relatórios diferentes não
 * diriam <em>por que</em> diferem: uma queda de qualidade tem causas distintas se veio de uma troca de
 * prompt ou de uma troca de modelo.
 */
public final class PromptRegressionReport {

    /** Pasta onde os relatórios são gravados. */
    public static final Path OUTPUT_DIRECTORY = Path.of("build", "reports", "prompt-regression");

    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC);

    private PromptRegressionReport() {
        // classe utilitária
    }

    /**
     * Grava o relatório e devolve o caminho do JSON.
     *
     * @param promptVersions versões de prompt ativas no momento da execução, por chave
     */
    public static Path write(
            ObjectMapper objectMapper,
            Instant executedAt,
            String model,
            Map<String, Integer> promptVersions,
            List<GoldenCaseResult> results) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("executedAt", executedAt.toString());
        report.put("model", model);
        report.put("promptVersions", promptVersions);
        report.put("summary", summary(results));
        report.put("cases", results);

        try {
            Files.createDirectories(OUTPUT_DIRECTORY);
            Path json = OUTPUT_DIRECTORY.resolve("report.json");
            byte[] content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
            Files.write(json, content);
            // Uma cópia com data no nome: o relatório "atual" é sempre o mesmo arquivo, e o histórico
            // fica ao lado para comparar execuções.
            Files.write(OUTPUT_DIRECTORY.resolve("report-%s.json".formatted(FILE_TIMESTAMP.format(executedAt))), content);
            Files.writeString(
                    OUTPUT_DIRECTORY.resolve("report.md"),
                    markdown(executedAt, model, promptVersions, results),
                    StandardCharsets.UTF_8);
            return json;
        } catch (IOException e) {
            throw new UncheckedIOException("não foi possível gravar o relatório de regressão", e);
        }
    }

    /** Números agregados da execução. */
    public static Map<String, Object> summary(List<GoldenCaseResult> results) {
        int expected = results.stream().mapToInt(GoldenCaseResult::expectedQuestions).sum();
        int correct = results.stream().mapToInt(GoldenCaseResult::correctQuestions).sum();
        int failedVerifications = results.stream().mapToInt(GoldenCaseResult::failedVerifications).sum();
        int factChecks = results.stream().mapToInt(GoldenCaseResult::factChecks).sum();
        int factMatches = results.stream().mapToInt(GoldenCaseResult::factMatches).sum();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cases", results.size());
        summary.put("passedCases", results.stream().filter(GoldenCaseResult::passed).count());
        summary.put("expectedQuestions", expected);
        summary.put("correctQuestions", correct);
        summary.put("correctQuestionRate", expected == 0 ? null : (double) correct / expected);
        summary.put("failedVerifications", failedVerifications);
        summary.put(
                "failedVerificationRate", expected == 0 ? null : (double) failedVerifications / expected);
        summary.put("factChecks", factChecks);
        summary.put("factMatchRate", factChecks == 0 ? null : (double) factMatches / factChecks);
        summary.put(
                "averageDurationMillis",
                results.stream().mapToLong(GoldenCaseResult::durationMillis).average().orElse(0.0));
        return summary;
    }

    private static String markdown(
            Instant executedAt, String model, Map<String, Integer> promptVersions, List<GoldenCaseResult> results) {
        Map<String, Object> summary = summary(results);
        StringBuilder text = new StringBuilder();
        text.append("# Regressão de prompts — ").append(executedAt).append("\n\n");
        text.append("- **Modelo:** ").append(model).append('\n');
        text.append("- **Versões de prompt:** ").append(promptVersions).append('\n');
        text.append("- **Casos:** ")
                .append(summary.get("passedCases"))
                .append(" de ")
                .append(summary.get("cases"))
                .append(" sem nenhuma divergência\n");
        text.append("- **Perguntas corretas:** ")
                .append(percentage(summary.get("correctQuestionRate")))
                .append(" (")
                .append(summary.get("correctQuestions"))
                .append(" de ")
                .append(summary.get("expectedQuestions"))
                .append(")\n");
        text.append("- **Respostas reprovadas na segunda checagem:** ")
                .append(percentage(summary.get("failedVerificationRate")))
                .append('\n');
        text.append("- **Fatos conferidos que bateram:** ")
                .append(percentage(summary.get("factMatchRate")))
                .append('\n');
        text.append("- **Tempo médio por caso:** ")
                .append(Math.round((double) summary.get("averageDurationMillis")))
                .append(" ms\n\n");

        text.append("| Caso | Situação | Perguntas corretas | Verificações reprovadas | Tempo |\n");
        text.append("|---|---|---|---|---|\n");
        for (GoldenCaseResult result : results) {
            text.append("| ").append(result.caseId())
                    .append(" | ").append(result.status())
                    .append(" | ").append(result.correctQuestions()).append('/').append(result.expectedQuestions())
                    .append(" | ").append(result.failedVerifications())
                    .append(" | ").append(result.durationMillis()).append(" ms |\n");
        }

        for (GoldenCaseResult result : results) {
            if (result.passed()) {
                continue;
            }
            text.append("\n## ").append(result.caseId()).append("\n\n");
            result.findings().forEach(finding -> text.append("- ").append(finding).append('\n'));
            result.questions().stream().filter(question -> !question.correct()).forEach(question -> {
                text.append("- **").append(question.questionKey()).append("**\n");
                question.failures().forEach(failure -> text.append("  - ").append(failure).append('\n'));
            });
        }
        return text.toString();
    }

    private static String percentage(Object rate) {
        return rate == null ? "—" : "%.1f%%".formatted((double) rate * 100);
    }
}
