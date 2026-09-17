package com.lexflow.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;

/**
 * Latência e taxa de erro das integrações externas (Prompt 18, item 2).
 *
 * <p>Um cronômetro por integração, com o desfecho como etiqueta: {@code success} ou o nome da
 * exceção. Com isso, taxa de erro e latência saem da mesma métrica, e é possível separar "o provedor
 * está lento" de "o provedor está recusando" sem instrumentar nada a mais.
 *
 * <p><strong>Nenhuma etiqueta carrega conteúdo.</strong> Modelo, operação e desfecho são valores de
 * cardinalidade baixa; identificador de demanda, prompt e texto de documento nunca entram em
 * métrica — além de violarem a seção 12, explodiriam a cardinalidade das séries.
 */
public class ExternalCallMetrics {

    /** Prefixo comum das métricas de integração externa. */
    public static final String METRIC_PREFIX = "lexflow.external.call";

    private final MeterRegistry meterRegistry;

    public ExternalCallMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** Marca o início de uma chamada. */
    public Timer.Sample start() {
        return Timer.start(meterRegistry);
    }

    /**
     * Registra o desfecho de uma chamada.
     *
     * @param integration nome da integração: {@code llm}, {@code embeddings}, {@code storage}
     * @param operation operação chamada, em cardinalidade baixa
     * @param detail detalhe de cardinalidade baixa (o modelo usado, por exemplo); pode ser nulo
     * @param error exceção que encerrou a chamada, ou nulo quando ela deu certo
     */
    public void record(Timer.Sample sample, String integration, String operation, String detail, Throwable error) {
        sample.stop(Timer.builder(METRIC_PREFIX)
                .description("Latência e desfecho das chamadas a serviços externos")
                .tag("integration", integration)
                .tag("operation", operation)
                .tag("detail", detail == null ? "none" : detail)
                .tag("outcome", error == null ? "success" : "error")
                .tag("error", error == null ? "none" : error.getClass().getSimpleName())
                .publishPercentileHistogram()
                .register(meterRegistry));
    }

    /** Conveniência para o caminho de sucesso. */
    public void recordSuccess(Timer.Sample sample, String integration, String operation, String detail) {
        record(sample, integration, operation, detail, null);
    }

    /** Acesso ao registro, para quem precisa criar métricas próprias. */
    public MeterRegistry registry() {
        return meterRegistry;
    }

    /** Converte uma duração já medida em nanossegundos, para quem já tem o tempo. */
    public static long toNanos(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }
}
