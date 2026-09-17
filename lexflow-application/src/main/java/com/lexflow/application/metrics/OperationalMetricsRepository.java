package com.lexflow.application.metrics;

import java.util.List;
import java.util.Map;

/**
 * Porta de leitura das métricas agregadas.
 *
 * <p>As consultas são agregações sobre o que já está gravado — não há tabela de métricas a manter, e
 * por isso não há como estes números divergirem do sistema que eles medem.
 */
public interface OperationalMetricsRepository {

    /** Quantas demandas há em cada status. */
    Map<String, Long> countCasesByStatus();

    /** Tempo médio, em segundos, da criação até a chegada à revisão humana, por tipo de demanda. */
    Map<String, Double> averageTimeToReviewSeconds();

    /** Alertas em aberto, por tipo. */
    Map<String, Long> countOpenAlertsByType();

    /** Uma linha por demanda decidida, com a sugestão implícita da IA e o desfecho humano. */
    List<AiHumanAgreement> findAgreements(int limit);
}
