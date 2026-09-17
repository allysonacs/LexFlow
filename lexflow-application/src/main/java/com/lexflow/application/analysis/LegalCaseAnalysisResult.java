package com.lexflow.application.analysis;

import com.lexflow.domain.ai.AiAnalysisResponse;
import com.lexflow.domain.alert.LegalCaseAlert;
import java.util.List;
import java.util.Objects;

/**
 * Resultado da análise de uma demanda.
 *
 * @param responses respostas persistidas, incluindo as de tentativas anteriores
 * @param openAlerts alertas em aberto ao fim da etapa; algum deles impede a demanda de avançar
 * @param llmCalls chamadas feitas ao LLM nesta execução, para o consumidor registrar o custo
 * @param verification o que a segunda checagem fez nesta execução (Prompt 14)
 * @param advanced indica se a demanda passou para {@code PENDING_HUMAN_REVIEW}
 */
public record LegalCaseAnalysisResult(
        List<AiAnalysisResponse> responses,
        List<LegalCaseAlert> openAlerts,
        int llmCalls,
        VerificationSummary verification,
        boolean advanced) {

    public LegalCaseAnalysisResult {
        responses = List.copyOf(Objects.requireNonNull(responses, "responses não pode ser nulo"));
        openAlerts = List.copyOf(Objects.requireNonNull(openAlerts, "openAlerts não pode ser nulo"));
        verification = verification == null ? VerificationSummary.NONE : verification;
    }

    /** Quantidade de respostas que vieram de regra de código, e não do modelo. */
    public long deterministicAnswerCount() {
        return responses.stream().filter(response -> !response.isFromLlm()).count();
    }
}
