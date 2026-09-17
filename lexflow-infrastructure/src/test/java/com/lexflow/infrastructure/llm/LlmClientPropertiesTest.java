package com.lexflow.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.infrastructure.observability.ExternalCallMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.lexflow.application.llm.LlmRequest;
import com.lexflow.application.llm.LlmRequestRejectedException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class LlmClientPropertiesTest {

    @Test
    @DisplayName("sem configuração, valem os padrões do provedor, com o recurso de recusa ligado")
    void shouldApplyDefaults() {
        LlmClientProperties properties = new LlmClientProperties(null, " ", null, null, null, null, null, null);

        assertThat(properties.baseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(properties.hasApiKey()).isFalse();
        assertThat(properties.anthropicVersion()).isEqualTo("2023-06-01");
        assertThat(properties.defaultModel()).isEqualTo("claude-opus-5");
        assertThat(properties.defaultMaxTokens()).isEqualTo(16_000);
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.responseTimeout()).isEqualTo(Duration.ofMinutes(3));
        assertThat(properties.refusalFallbackEnabled()).isTrue();
        assertThat(properties.refusalFallback()).isEqualTo("default");
        assertThat(properties.toString()).contains("(ausente)");
    }

    @Test
    @DisplayName("valores informados são normalizados; vazio desliga o recurso de recusa; a chave não vaza")
    void shouldNormalizeValues() {
        LlmClientProperties properties = new LlmClientProperties(
                "http://localhost:8089/ ", " segredo ", "2023-06-01", "claude-sonnet-5", 1024,
                Duration.ofSeconds(1), Duration.ofSeconds(2), "");

        assertThat(properties.baseUrl()).isEqualTo("http://localhost:8089");
        assertThat(properties.apiKey()).isEqualTo("segredo");
        assertThat(properties.refusalFallbackEnabled()).isFalse();
        assertThat(properties.toString()).contains("***").doesNotContain("segredo");
    }

    @Test
    @DisplayName("limites inválidos impedem a inicialização")
    void shouldRejectInvalidValues() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LlmClientProperties(null, null, null, null, 0, null, null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LlmClientProperties(null, null, null, null, null, Duration.ZERO, null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LlmClientProperties(null, null, null, null, null, null, Duration.ofSeconds(-1), null));
    }

    @Test
    @DisplayName("sem chave de API, a chamada é recusada antes de sair da aplicação")
    void shouldRejectCallsWithoutApiKey() {
        ObjectMapper objectMapper = new ObjectMapper();
        AnthropicMessagesClient client = new AnthropicMessagesClient(
                WebClient.create("http://localhost:1"),
                new LlmClientProperties(null, null, null, null, null, null, null, null),
                new JsonSchemaResponseValidator(objectMapper),
                objectMapper,
                Retry.ofDefaults("teste"),
                CircuitBreaker.ofDefaults("teste"),
                TimeLimiter.ofDefaults("teste"),
                Bulkhead.ofDefaults("teste"),
                new ExternalCallMetrics(new SimpleMeterRegistry()),
                Clock.systemUTC());

        assertThatExceptionOfType(LlmRequestRejectedException.class)
                .isThrownBy(() -> client.complete(LlmRequest.text(null, "Olá")))
                .withMessageContaining("api-key")
                .satisfies(e -> assertThat(e.statusCode()).isZero());
    }

    @Test
    @DisplayName("o validador aceita só schemas que são objetos JSON")
    void shouldRequireObjectSchema() {
        JsonSchemaResponseValidator validator = new JsonSchemaResponseValidator(new ObjectMapper());

        assertThatIllegalArgumentException().isThrownBy(() -> validator.parseSchema("[1, 2]"));
        assertThat(validator.requireValid("{\"type\":\"object\"}", "{\"a\": 1}", "m")).isEqualTo("{\"a\":1}");
        // Pela porta, violações viram lista — inclusive JSON ilegível —, sem exceção.
        assertThat(validator.violations("{\"type\":\"object\"}", "[]")).isNotEmpty();
        assertThat(validator.violations("{\"type\":\"object\"}", "{ quebrado")).singleElement()
                .satisfies(violation -> assertThat(violation).startsWith("JSON inválido"));
        assertThat(validator.violations("{\"type\":\"object\"}", "{}")).isEmpty();
    }
}
