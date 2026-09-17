package com.lexflow.application.metrics;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resumo da concordância entre a IA e o revisor humano (Prompt 18, item 3).
 *
 * @param agreementRate proporção de concordância entre as demandas com sugestão conclusiva; nula
 *     quando ainda não há nenhuma — um zero ali seria lido como "a IA erra sempre"
 * @param byCaseType concordância por tipo de demanda: é onde uma queda aparece antes de aparecer no
 *     número geral
 */
public record AiHumanAgreementSummary(
        long decidedCases,
        long conclusiveCases,
        long agreements,
        long disagreements,
        Double agreementRate,
        Map<String, Double> byCaseType,
        List<AiHumanAgreement> disagreementSamples) {

    public AiHumanAgreementSummary {
        byCaseType = Map.copyOf(Objects.requireNonNull(byCaseType, "byCaseType não pode ser nulo"));
        disagreementSamples =
                List.copyOf(Objects.requireNonNull(disagreementSamples, "disagreementSamples não pode ser nulo"));
    }
}
