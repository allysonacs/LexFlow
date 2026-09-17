package com.lexflow.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.netty.channel.ChannelOption;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Monta o cliente LLM.
 *
 * <p>O {@link WebClient} é próprio deste cliente, e não o compartilhado da aplicação: os timeouts e o
 * endereço base são do provedor de LLM e não devem vazar para outras integrações.
 *
 * <p>Retry, circuit breaker, time limiter e bulkhead vêm dos registros do Resilience4j, configurados
 * no {@code application.yml} em {@code resilience4j.*.instances.llm}. Uma instância sem configuração
 * explícita usa os padrões da biblioteca.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmClientProperties.class)
public class LlmClientConfiguration {

    /** Validador de JSON Schema, usado pelo cliente e, pela porta, pelos casos de uso. */
    @Bean
    public JsonSchemaResponseValidator jsonSchemaResponseValidator(ObjectMapper objectMapper) {
        return new JsonSchemaResponseValidator(objectMapper);
    }

    @Bean
    public AnthropicMessagesClient anthropicMessagesClient(
            LlmClientProperties properties,
            ObjectMapper objectMapper,
            JsonSchemaResponseValidator validator,
            RetryRegistry retryRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            TimeLimiterRegistry timeLimiterRegistry,
            BulkheadRegistry bulkheadRegistry,
            Clock clock) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.connectTimeout().toMillis())
                .responseTimeout(properties.responseTimeout());
        WebClient webClient = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        String instance = AnthropicMessagesClient.RESILIENCE_INSTANCE;
        return new AnthropicMessagesClient(
                webClient,
                properties,
                validator,
                objectMapper,
                retryRegistry.retry(instance),
                circuitBreakerRegistry.circuitBreaker(instance),
                timeLimiterRegistry.timeLimiter(instance),
                bulkheadRegistry.bulkhead(instance),
                clock);
    }
}
