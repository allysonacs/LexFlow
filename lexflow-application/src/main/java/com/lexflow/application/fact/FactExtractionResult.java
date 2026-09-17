package com.lexflow.application.fact;

import com.lexflow.domain.ai.AiExtractedFact;
import com.lexflow.domain.alert.LegalCaseAlert;
import java.util.List;
import java.util.Objects;

/**
 * Resultado da extração de fatos de uma demanda.
 *
 * @param facts todos os fatos da demanda, inclusive os de tentativas anteriores
 * @param openAlerts alertas em aberto da demanda, que a impedem de avançar
 * @param llmCalls chamadas ao LLM feitas nesta execução
 * @param advanced {@code true} quando a demanda passou para {@code AI_ANALYSIS_IN_PROGRESS} nesta execução
 */
public record FactExtractionResult(
        List<AiExtractedFact> facts, List<LegalCaseAlert> openAlerts, int llmCalls, boolean advanced) {

    public FactExtractionResult {
        facts = List.copyOf(Objects.requireNonNull(facts, "facts não pode ser nulo"));
        openAlerts = List.copyOf(Objects.requireNonNull(openAlerts, "openAlerts não pode ser nulo"));
    }

    /** Indica se a demanda ficou parada à espera de atenção humana. */
    public boolean blockedByAlerts() {
        return !openAlerts.isEmpty();
    }
}
