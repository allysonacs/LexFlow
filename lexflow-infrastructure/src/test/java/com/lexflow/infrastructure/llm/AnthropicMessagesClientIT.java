package com.lexflow.infrastructure.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.lexflow.application.llm.LlmClientPort;
import com.lexflow.application.llm.LlmEffort;
import com.lexflow.application.llm.LlmRefusalException;
import com.lexflow.application.llm.LlmRequest;
import com.lexflow.application.llm.LlmRequestRejectedException;
import com.lexflow.application.llm.LlmResponse;
import com.lexflow.application.llm.LlmResponseValidationException;
import com.lexflow.application.llm.LlmStopReason;
import com.lexflow.application.llm.LlmUnavailableException;
import com.lexflow.infrastructure.config.InfrastructureConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterAutoConfiguration;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Cliente LLM contra um provedor simulado com WireMock (Prompt 10).
 *
 * <p>Sobe só o necessário: o cliente, o Jackson e a autoconfiguração real do Resilience4j, lendo as
 * propriedades {@code resilience4j.*.instances.llm} — assim o teste cobre também a configuração, e
 * não apenas o código. Os tempos são curtos para o teste rodar em segundos; a proporção entre eles é
 * a mesma da produção.
 *
 * <p>Não precisa de Docker nem de chave de API real.
 */
@SpringBootTest(
        classes = AnthropicMessagesClientIT.LlmTestApplication.class,
        properties = {
            "lexflow.llm.api-key=chave-de-teste",
            "lexflow.llm.default-model=claude-opus-5",
            "lexflow.llm.default-max-tokens=2048",
            "lexflow.llm.response-timeout=3s",
            "resilience4j.timelimiter.instances.llm.timeout-duration=700ms",
            "resilience4j.retry.instances.llm.max-attempts=3",
            "resilience4j.retry.instances.llm.wait-duration=10ms",
            "resilience4j.retry.instances.llm.enable-exponential-backoff=true",
            "resilience4j.retry.instances.llm.exponential-backoff-multiplier=2",
            "resilience4j.retry.instances.llm.retry-exceptions[0]=com.lexflow.application.llm.LlmUnavailableException",
            "resilience4j.retry.instances.llm.retry-exceptions[1]=java.util.concurrent.TimeoutException",
            "resilience4j.retry.instances.llm.retry-exceptions[2]=io.github.resilience4j.bulkhead.BulkheadFullException",
            "resilience4j.circuitbreaker.instances.llm.sliding-window-type=COUNT_BASED",
            "resilience4j.circuitbreaker.instances.llm.sliding-window-size=4",
            "resilience4j.circuitbreaker.instances.llm.minimum-number-of-calls=4",
            "resilience4j.circuitbreaker.instances.llm.failure-rate-threshold=50",
            "resilience4j.circuitbreaker.instances.llm.wait-duration-in-open-state=1s",
            "resilience4j.circuitbreaker.instances.llm.permitted-number-of-calls-in-half-open-state=1",
            "resilience4j.circuitbreaker.instances.llm.automatic-transition-from-open-to-half-open-enabled=true",
            "resilience4j.circuitbreaker.instances.llm.record-exceptions[0]=com.lexflow.application.llm.LlmUnavailableException",
            "resilience4j.circuitbreaker.instances.llm.record-exceptions[1]=java.util.concurrent.TimeoutException",
            "resilience4j.circuitbreaker.instances.llm.ignore-exceptions[0]=com.lexflow.application.llm.LlmRequestRejectedException",
            "resilience4j.circuitbreaker.instances.llm.ignore-exceptions[1]=io.github.resilience4j.bulkhead.BulkheadFullException",
            "resilience4j.bulkhead.instances.llm.max-concurrent-calls=1",
            "resilience4j.bulkhead.instances.llm.max-wait-duration=0"
        })
class AnthropicMessagesClientIT {

    private static final String PATH = AnthropicMessagesClient.MESSAGES_PATH;

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "parties": {"type": "array", "items": {"type": "string"}},
                "totalAmount": {"type": ["number", "null"]}
              },
              "required": ["parties", "totalAmount"],
              "additionalProperties": false
            }
            """;

    @RegisterExtension
    static WireMockExtension provider = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        registry.add("lexflow.llm.base-url", provider::baseUrl);
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration({
        JacksonAutoConfiguration.class,
        AopAutoConfiguration.class,
        RetryAutoConfiguration.class,
        CircuitBreakerAutoConfiguration.class,
        TimeLimiterAutoConfiguration.class,
        BulkheadAutoConfiguration.class
    })
    @Import({LlmClientConfiguration.class, InfrastructureConfiguration.class})
    static class LlmTestApplication {}

    @Autowired
    private LlmClientPort client;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void resetState() {
        provider.resetAll();
        circuitBreaker = circuitBreakerRegistry.circuitBreaker(AnthropicMessagesClient.RESILIENCE_INSTANCE);
        circuitBreaker.reset();
    }

    /** Corpo de resposta no formato da Messages API. */
    private static String messageBody(String model, String stopReason, String text) {
        return """
                {
                  "id": "msg_01ABC",
                  "type": "message",
                  "role": "assistant",
                  "model": "%s",
                  "content": [{"type": "text", "text": %s}],
                  "stop_reason": "%s",
                  "stop_details": null,
                  "usage": {"input_tokens": 120, "output_tokens": 45, "cache_read_input_tokens": 10, "cache_creation_input_tokens": 0}
                }
                """.formatted(model, jsonString(text), stopReason);
    }

    private static String jsonString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void stubSuccess(String text) {
        provider.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("request-id", "req_123")
                .withBody(messageBody("claude-opus-5", "end_turn", text))));
    }

    private static void stubError(int status, String type) {
        provider.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"type": "error", "error": {"type": "%s", "message": "falha simulada"}, "request_id": "req_err"}
                        """.formatted(type))));
    }

    private static LlmRequest structuredRequest() {
        return LlmRequest.structured(
                "Extraia apenas fatos presentes no texto.",
                "Contrato entre Empresa A e Empresa B, no valor de R$ 1.000,00.",
                SCHEMA);
    }

    private static int requestCount() {
        return provider.findAll(postRequestedFor(urlEqualTo(PATH))).size();
    }

    @Test
    @DisplayName("resposta estruturada válida é devolvida validada, com modelo, uso e identificadores")
    void shouldReturnValidatedStructuredOutput() {
        stubSuccess("{\"parties\": [\"Empresa A\", \"Empresa B\"], \"totalAmount\": 1000.0}");

        LlmResponse response = client.complete(structuredRequest().withEffort(LlmEffort.HIGH));

        assertThat(response.model()).isEqualTo("claude-opus-5");
        assertThat(response.requestedModel()).isEqualTo("claude-opus-5");
        assertThat(response.servedByFallbackModel()).isFalse();
        assertThat(response.stopReason()).isEqualTo(LlmStopReason.END_TURN);
        assertThat(response.structuredOutput())
                .isEqualTo("{\"parties\":[\"Empresa A\",\"Empresa B\"],\"totalAmount\":1000.0}");
        assertThat(response.usage().inputTokens()).isEqualTo(120);
        assertThat(response.usage().outputTokens()).isEqualTo(45);
        assertThat(response.usage().cacheReadInputTokens()).isEqualTo(10);
        assertThat(response.messageId()).isEqualTo("msg_01ABC");
        assertThat(response.requestId()).isEqualTo("req_123");
        assertThat(response.latency()).isPositive();
        // O toString não carrega o conteúdo.
        assertThat(response.toString()).doesNotContain("Empresa A");

        provider.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withHeader("x-api-key", equalTo("chave-de-teste"))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .withHeader("anthropic-beta", equalTo(LlmClientProperties.REFUSAL_FALLBACK_BETA))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {
                          "model": "claude-opus-5",
                          "max_tokens": 2048,
                          "system": "Extraia apenas fatos presentes no texto.",
                          "messages": [{"role": "user", "content": "Contrato entre Empresa A e Empresa B, no valor de R$ 1.000,00."}],
                          "output_config": {"format": {"type": "json_schema"}, "effort": "high"},
                          "fallbacks": "default"
                        }
                        """, false, true))
                .withRequestBody(matchingJsonPath("$.output_config.format.schema.required[0]", equalTo("parties")))
                .withRequestBody(notContaining("temperature")));
    }

    @Test
    @DisplayName("resposta de texto livre junta os blocos de texto e registra o modelo que efetivamente respondeu")
    void shouldReturnFreeTextAndRecordServedModel() {
        provider.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {
                          "id": "msg_02",
                          "model": "claude-opus-4-8",
                          "content": [
                            {"type": "thinking", "thinking": "", "signature": "abc"},
                            {"type": "fallback", "from": {"model": "claude-opus-5"}, "to": {"model": "claude-opus-4-8"}},
                            {"type": "text", "text": "Primeira parte. "},
                            {"type": "text", "text": "Segunda parte."}
                          ],
                          "stop_reason": "max_tokens",
                          "usage": {"input_tokens": 5, "output_tokens": 7}
                        }
                        """)));

        LlmResponse response = client.complete(new LlmRequest(
                null, "Resuma.", null, "claude-opus-5", 64, 0.2, null));

        assertThat(response.text()).isEqualTo("Primeira parte. Segunda parte.");
        assertThat(response.structuredOutput()).isNull();
        assertThat(response.model()).isEqualTo("claude-opus-4-8");
        assertThat(response.servedByFallbackModel()).isTrue();
        // Em texto livre, a resposta truncada é devolvida e quem chama decide.
        assertThat(response.isTruncated()).isTrue();
        assertThat(response.usage().cacheReadInputTokens()).isZero();
        assertThat(response.requestId()).isNull();
        provider.verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.temperature", equalTo("0.2")))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("64")))
                .withRequestBody(matchingJsonPath("$.system", absent()))
                .withRequestBody(matchingJsonPath("$.output_config", absent())));
    }

    @Test
    @DisplayName("resposta que não é JSON é recusada, sem nova tentativa e sem afetar o circuito")
    void shouldRejectMalformedJsonWithoutRetry() {
        stubSuccess("Aqui estão os fatos: partes A e B");

        assertThatExceptionOfType(LlmResponseValidationException.class)
                .isThrownBy(() -> client.complete(structuredRequest()))
                .withMessageContaining("não é JSON")
                .satisfies(e -> assertThat(e.model()).isEqualTo("claude-opus-5"));

        assertThat(requestCount()).isEqualTo(1);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("JSON que viola o schema é recusado, com as violações descritas e sem os valores")
    void shouldRejectSchemaViolation() {
        stubSuccess("{\"parties\": \"Empresa A\", \"extra\": \"CPF 123.456.789-00\"}");

        assertThatExceptionOfType(LlmResponseValidationException.class)
                .isThrownBy(() -> client.complete(structuredRequest()))
                .withMessageContaining("schema")
                .satisfies(e -> {
                    assertThat(e.violations()).isNotEmpty();
                    assertThat(String.join(" ", e.violations()))
                            .contains("parties")
                            .contains("totalAmount")
                            .doesNotContain("123.456.789-00");
                });
        assertThat(requestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("resposta estruturada cortada pelo limite de tokens nunca é aceita")
    void shouldRejectTruncatedStructuredOutput() {
        provider.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(messageBody("claude-opus-5", "max_tokens", "{\"parties\": [\"Empresa A\""))));

        assertThatExceptionOfType(LlmResponseValidationException.class)
                .isThrownBy(() -> client.complete(structuredRequest()))
                .withMessageContaining("limite de tokens");
    }

    @Test
    @DisplayName("recusa do modelo vira LlmRefusalException, com a categoria informada")
    void shouldReportRefusal() {
        provider.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id": "msg_03", "model": "claude-opus-5", "content": [], "stop_reason": "refusal",
                         "stop_details": {"type": "refusal", "category": "cyber", "explanation": null},
                         "usage": {"input_tokens": 3, "output_tokens": 0}}
                        """)));

        assertThatExceptionOfType(LlmRefusalException.class)
                .isThrownBy(() -> client.complete(structuredRequest()))
                .satisfies(e -> {
                    assertThat(e.category()).isEqualTo("cyber");
                    assertThat(e.model()).isEqualTo("claude-opus-5");
                });
        assertThat(requestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("erro 5xx transitório é repetido com backoff até dar certo")
    void shouldRetryTransientServerErrors() {
        String scenario = "instável";
        provider.stubFor(post(urlEqualTo(PATH)).inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500).withBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"x\"}}"))
                .willSetStateTo("sobrecarregado"));
        provider.stubFor(post(urlEqualTo(PATH)).inScenario(scenario)
                .whenScenarioStateIs("sobrecarregado")
                .willReturn(aResponse().withStatus(529).withBody("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"x\"}}"))
                .willSetStateTo("recuperado"));
        provider.stubFor(post(urlEqualTo(PATH)).inScenario(scenario)
                .whenScenarioStateIs("recuperado")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(messageBody("claude-opus-5", "end_turn", "ok"))));

        LlmResponse response = client.complete(LlmRequest.text(null, "Olá"));

        assertThat(response.text()).isEqualTo("ok");
        assertThat(requestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("provedor sobrecarregado o tempo todo esgota as tentativas e responde indisponível")
    void shouldGiveUpAfterMaxAttempts() {
        stubError(529, "overloaded_error");

        assertThatExceptionOfType(LlmUnavailableException.class)
                .isThrownBy(() -> client.complete(LlmRequest.text(null, "Olá")))
                .withMessageContaining("529")
                .withMessageContaining("overloaded_error")
                .satisfies(e -> assertThat(e.statusCode()).isEqualTo(529));
        assertThat(requestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("limite de uso (429) também é transitório")
    void shouldRetryRateLimit() {
        stubError(429, "rate_limit_error");

        assertThatExceptionOfType(LlmUnavailableException.class)
                .isThrownBy(() -> client.complete(LlmRequest.text(null, "Olá")))
                .satisfies(e -> assertThat(e.statusCode()).isEqualTo(429));
        assertThat(requestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("pedido recusado (4xx) não é repetido nem conta para abrir o circuito")
    void shouldNotRetryClientErrors() {
        stubError(400, "invalid_request_error");

        assertThatExceptionOfType(LlmRequestRejectedException.class)
                .isThrownBy(() -> client.complete(LlmRequest.text(null, "Olá")))
                .satisfies(e -> {
                    assertThat(e.statusCode()).isEqualTo(400);
                    assertThat(e.errorType()).isEqualTo("invalid_request_error");
                    assertThat(e.getMessage()).contains("falha simulada").contains("req_err");
                });
        assertThat(requestCount()).isEqualTo(1);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("provedor lento demais é cortado pelo time limiter em cada tentativa")
    void shouldTimeOutSlowProvider() {
        provider.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(1_500)
                .withHeader("Content-Type", "application/json")
                .withBody(messageBody("claude-opus-5", "end_turn", "tarde demais"))));
        long startedAt = System.nanoTime();

        assertThatExceptionOfType(LlmUnavailableException.class)
                .isThrownBy(() -> client.complete(LlmRequest.text(null, "Olá")))
                .withMessageContaining("tempo limite");

        // Três tentativas de 700 ms, e não três de 1,5 s: o corte é do time limiter.
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofMillis(4_000));
        assertThat(requestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("falha de conexão é tratada como indisponibilidade transitória")
    void shouldTreatConnectionResetAsUnavailable() {
        provider.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatExceptionOfType(LlmUnavailableException.class)
                .isThrownBy(() -> client.complete(LlmRequest.text(null, "Olá")))
                .withMessageContaining("comunicação")
                .satisfies(e -> assertThat(e.statusCode()).isZero());
        assertThat(requestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("com falhas seguidas o circuito abre, falha na hora sem chamar o provedor e fecha quando ele volta")
    void shouldOpenAndCloseCircuit() {
        stubError(500, "api_error");

        // Duas chamadas com três tentativas cada: quatro falhas já bastam para a janela configurada.
        for (int call = 0; call < 2; call++) {
            assertThatExceptionOfType(LlmUnavailableException.class)
                    .isThrownBy(() -> client.complete(LlmRequest.text(null, "Olá")));
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsWhileClosed = requestCount();

        long startedAt = System.nanoTime();
        assertThatExceptionOfType(LlmUnavailableException.class)
                .isThrownBy(() -> client.complete(LlmRequest.text(null, "Olá")))
                .withMessageContaining("Circuito");
        // Circuito aberto: falha imediata, sem nenhuma requisição ao provedor.
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofMillis(500));
        assertThat(requestCount()).isEqualTo(callsWhileClosed);

        // O provedor volta; passado o tempo de espera, o circuito deixa uma chamada de teste passar.
        provider.resetAll();
        stubSuccess("de volta");
        await().atMost(Duration.ofSeconds(5))
                .until(() -> circuitBreaker.getState() == CircuitBreaker.State.HALF_OPEN);

        assertThat(client.complete(LlmRequest.text(null, "Olá")).text()).isEqualTo("de volta");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("uma chamada lenta não trava as demais: o excesso falha rápido, sem esperar a lenta")
    void shouldIsolateConcurrentCallsWithBulkhead() throws Exception {
        provider.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(400)
                .withHeader("Content-Type", "application/json")
                .withBody(messageBody("claude-opus-5", "end_turn", "lenta"))));

        CompletableFuture<LlmResponse> slow =
                CompletableFuture.supplyAsync(() -> client.complete(LlmRequest.text(null, "lenta")));
        await().atMost(Duration.ofSeconds(2)).until(() -> requestCount() == 1);

        long startedAt = System.nanoTime();
        assertThatExceptionOfType(LlmUnavailableException.class)
                .isThrownBy(() -> client.complete(LlmRequest.text(null, "rápida")))
                .withMessageContaining("simultâneas");
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofMillis(300));

        assertThat(slow.get(3, TimeUnit.SECONDS).text()).isEqualTo("lenta");
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("schema que não é JSON é recusado antes de qualquer chamada")
    void shouldRejectInvalidSchemaBeforeCalling() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> client.complete(LlmRequest.structured(null, "Olá", "{ não é json")))
                .withMessageContaining("outputSchema");
        assertThat(requestCount()).isZero();
    }
}
