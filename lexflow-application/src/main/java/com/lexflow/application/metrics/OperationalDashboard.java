package com.lexflow.application.metrics;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Dados agregados para um painel (Prompt 18, item 5).
 *
 * <p>É JSON, e não um painel: quem desenha gráfico é o Grafana ou o Metabase. O que o sistema deve é
 * entregar o número certo, em um endereço estável.
 *
 * @param casesByStatus quantas demandas há em cada status — a fila de trabalho, em uma linha
 * @param averageTimeToReviewSeconds tempo médio da criação até a revisão humana, por tipo de demanda
 * @param openAlertsByType alertas em aberto por tipo: o que está segurando demandas agora
 */
public record OperationalDashboard(
        Instant generatedAt,
        Map<String, Long> casesByStatus,
        Map<String, Double> averageTimeToReviewSeconds,
        Map<String, Long> openAlertsByType,
        AiHumanAgreementSummary agreement) {

    public OperationalDashboard {
        Objects.requireNonNull(generatedAt, "generatedAt não pode ser nulo");
        casesByStatus = Map.copyOf(Objects.requireNonNull(casesByStatus, "casesByStatus não pode ser nulo"));
        averageTimeToReviewSeconds = Map.copyOf(
                Objects.requireNonNull(averageTimeToReviewSeconds, "averageTimeToReviewSeconds não pode ser nulo"));
        openAlertsByType = Map.copyOf(Objects.requireNonNull(openAlertsByType, "openAlertsByType não pode ser nulo"));
        Objects.requireNonNull(agreement, "agreement não pode ser nulo");
    }
}
