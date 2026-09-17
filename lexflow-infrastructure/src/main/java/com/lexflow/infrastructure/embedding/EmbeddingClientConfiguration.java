package com.lexflow.infrastructure.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.ChannelOption;
import com.lexflow.infrastructure.observability.ExternalCallMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Monta o cliente de embeddings.
 *
 * <p>O {@link WebClient} é próprio desta integração, como no cliente LLM: endereço e timeouts são do
 * provedor de embeddings e não devem vazar para outras chamadas.
 *
 * <p>O bean só é criado se nenhum outro {@code EmbeddingClientPort} existir, o que permite aos testes
 * substituírem o provedor por um modelo determinístico sem tocar nesta classe.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmbeddingClientProperties.class)
public class EmbeddingClientConfiguration {

    @Bean
    @ConditionalOnMissingBean(com.lexflow.application.knowledge.EmbeddingClientPort.class)
    public VoyageEmbeddingClient voyageEmbeddingClient(
            EmbeddingClientProperties properties,
            ObjectMapper objectMapper,
            RetryRegistry retryRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            TimeLimiterRegistry timeLimiterRegistry,
            BulkheadRegistry bulkheadRegistry,
            ObjectProvider<MeterRegistry> meterRegistry) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.connectTimeout().toMillis())
                .responseTimeout(properties.responseTimeout());
        WebClient webClient = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        String instance = VoyageEmbeddingClient.RESILIENCE_INSTANCE;
        return new VoyageEmbeddingClient(
                webClient,
                properties,
                objectMapper,
                retryRegistry.retry(instance),
                circuitBreakerRegistry.circuitBreaker(instance),
                timeLimiterRegistry.timeLimiter(instance),
                bulkheadRegistry.bulkhead(instance),
                new ExternalCallMetrics(meterRegistry.getIfAvailable(SimpleMeterRegistry::new)));
    }
}
