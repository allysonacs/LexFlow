package com.lexflow.api.regression;

import java.util.List;

/**
 * O que aconteceu com um caso dourado em uma execução (Prompt 19, item 3).
 *
 * @param status {@code COMPLETED} quando a demanda chegou à revisão humana; {@code BLOCKED} quando
 *     parou com alerta; {@code ERROR} quando a execução falhou
 * @param durationMillis tempo do envio da demanda até o fim da análise
 */
public record GoldenCaseResult(
        String caseId,
        String caseType,
        String status,
        long durationMillis,
        int expectedQuestions,
        int correctQuestions,
        int failedVerifications,
        int factChecks,
        int factMatches,
        List<String> findings,
        List<QuestionResult> questions) {

    /** Resultado de uma pergunta: correta ou não, e por quê. */
    public record QuestionResult(
            String questionKey,
            boolean correct,
            String answerSource,
            String verificationStatus,
            double confidenceScore,
            int citedChunks,
            List<String> failures) {}

    /** Percentual de perguntas corretas; nulo quando o caso não declarou nenhuma. */
    public Double correctQuestionRate() {
        return expectedQuestions == 0 ? null : (double) correctQuestions / expectedQuestions;
    }

    /** Percentual de fatos conferidos que bateram com o gabarito. */
    public Double factMatchRate() {
        return factChecks == 0 ? null : (double) factMatches / factChecks;
    }

    public boolean passed() {
        return "COMPLETED".equals(status) && correctQuestions == expectedQuestions && findings.isEmpty();
    }
}
