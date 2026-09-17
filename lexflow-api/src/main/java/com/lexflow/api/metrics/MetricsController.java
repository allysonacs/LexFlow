package com.lexflow.api.metrics;

import com.lexflow.application.metrics.AiHumanAgreementSummary;
import com.lexflow.application.metrics.FindOperationalMetricsService;
import com.lexflow.application.metrics.OperationalDashboard;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Métricas agregadas do sistema (Prompt 18, itens 3 e 5).
 *
 * <p>Devolve JSON, e não um painel: quem desenha gráfico é o Grafana ou o Metabase. O que o sistema
 * deve é entregar o número certo, em um endereço estável.
 *
 * <p>As métricas técnicas — latência e erro das integrações, tamanho de fila, tempo por tipo de
 * demanda — saem em {@code /actuator/prometheus}. Aqui ficam as de negócio, que precisam de contexto
 * jurídico para serem calculadas.
 */
@RestController
@RequestMapping(path = MetricsController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class MetricsController {

    public static final String BASE_PATH = "/api/v1/metrics";

    private final FindOperationalMetricsService metricsService;

    public MetricsController(FindOperationalMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /**
     * Concordância entre a sugestão implícita da IA e a decisão humana.
     *
     * <p><strong>A IA não emite essa sugestão.</strong> Ela é uma leitura do conjunto das respostas,
     * feita pelo sistema; a decisão continua sendo inteiramente de uma pessoa. O número serve para
     * acompanhar, ao longo do tempo, se a análise e a decisão caminham juntas — uma queda aqui é
     * sinal de que o prompt, o modelo ou a base normativa merecem atenção.
     */
    @GetMapping("/ai-human-agreement")
    public AiHumanAgreementSummary agreement() {
        return metricsService.agreement();
    }

    /** Dados agregados para um painel: fila por status, tempo por tipo, alertas e concordância. */
    @GetMapping("/dashboard")
    public OperationalDashboard dashboard() {
        return metricsService.dashboard();
    }
}
