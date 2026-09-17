package com.lexflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.lexflow.api.AbstractApiIT;
import com.lexflow.application.llm.LlmClientPort;
import com.lexflow.infrastructure.llm.AnthropicMessagesClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Verifica que o contexto da aplicação sobe e que o health check responde {@code UP}.
 *
 * <p>Desde o Prompt 03 a aplicação depende de um PostgreSQL com as migrations aplicadas, então o
 * teste sobe containers reais em vez de tentar conectar aos serviços locais. É preciso ter o Docker
 * em execução.
 */
class LexFlowApplicationTest extends AbstractApiIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthEndpointShouldReturnUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void healthEndpointShouldReportDatabaseComponent() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getBody()).contains("\"db\"");
    }

    @Autowired
    private LlmClientPort llmClient;

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private TimeLimiterRegistry timeLimiterRegistry;

    @Test
    @DisplayName("o cliente LLM sobe com a resiliência lida do application.yml, mesmo sem chave de API")
    void llmClientShouldBeConfiguredFromApplicationYml() {
        String instance = AnthropicMessagesClient.RESILIENCE_INSTANCE;

        assertThat(llmClient).isInstanceOf(AnthropicMessagesClient.class);
        assertThat(retryRegistry.retry(instance).getRetryConfig().getMaxAttempts()).isEqualTo(3);
        assertThat(circuitBreakerRegistry.circuitBreaker(instance).getCircuitBreakerConfig().getSlidingWindowSize())
                .isEqualTo(20);
        assertThat(timeLimiterRegistry.timeLimiter(instance).getTimeLimiterConfig().getTimeoutDuration())
                .isEqualTo(Duration.ofSeconds(180));
    }
}
