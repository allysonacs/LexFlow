package com.lexflow.infrastructure.embedding;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.lexflow.application.knowledge.EmbeddingClientPort;
import com.lexflow.application.knowledge.EmbeddingRequestRejectedException;
import com.lexflow.application.knowledge.EmbeddingUnavailableException;
import com.lexflow.domain.knowledge.Embedding;
import com.lexflow.infrastructure.config.InfrastructureConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterAutoConfiguration;
import java.util.List;
import java.util.StringJoiner;
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
 * Cliente de embeddings contra um provedor simulado com WireMock (Prompt 12).
 *
 * <p>Mesma montagem do teste do cliente LLM: só o cliente, o Jackson e a autoconfiguração real do
 * Resilience4j, lendo {@code resilience4j.*.instances.embeddings}. Não precisa de Docker nem de chave
 * de API real.
 */
@SpringBootTest(
        classes = VoyageEmbeddingClientIT.EmbeddingTestApplication.class,
        properties = {
            "lexflow.embeddings.api-key=chave-de-teste",
            "lexflow.embeddings.model=voyage-3-large",
            "lexflow.embeddings.dimension=4",
            "lexflow.embeddings.batch-size=2",
            "lexflow.embeddings.response-timeout=3s",
            "resilience4j.timelimiter.instances.embeddings.timeout-duration=2s",
            "resilience4j.retry.instances.embeddings.max-attempts=3",
            "resilience4j.retry.instances.embeddings.wait-duration=10ms",
            "resilience4j.retry.instances.embeddings.retry-exceptions[0]=com.lexflow.application.knowledge.EmbeddingUnavailableException",
            "resilience4j.retry.instances.embeddings.retry-exceptions[1]=java.util.concurrent.TimeoutException",
            "resilience4j.circuitbreaker.instances.embeddings.sliding-window-size=10",
            "resilience4j.circuitbreaker.instances.embeddings.minimum-number-of-calls=10",
            "resilience4j.circuitbreaker.instances.embeddings.record-exceptions[0]=com.lexflow.application.knowledge.EmbeddingUnavailableException",
            "resilience4j.circuitbreaker.instances.embeddings.ignore-exceptions[0]=com.lexflow.application.knowledge.EmbeddingRequestRejectedException",
            "resilience4j.bulkhead.instances.embeddings.max-concurrent-calls=4"
        })
class VoyageEmbeddingClientIT {

    private static final String PATH = "/v1/embeddings";

    @RegisterExtension
    static WireMockExtension provider =
            WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        registry.add("lexflow.embeddings.base-url", provider::baseUrl);
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
    @Import({EmbeddingClientConfiguration.class, InfrastructureConfiguration.class})
    static class EmbeddingTestApplication {}

    @Autowired
    private EmbeddingClientPort client;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetState() {
        provider.resetAll();
        circuitBreakerRegistry.circuitBreaker(VoyageEmbeddingClient.RESILIENCE_INSTANCE).reset();
    }

    @Test
    @DisplayName("uma consulta vira um vetor, e o pedido informa modelo, dimensão e tipo de entrada")
    void shouldEmbedQuery() {
        stubSuccess(List.of(new float[] {0.1f, 0.2f, 0.3f, 0.4f}));

        Embedding embedding = client.embedQuery("Podemos assinar esse contrato?");

        assertThat(embedding.dimension()).isEqualTo(4);
        assertThat(embedding.toArray()).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        provider.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer chave-de-teste"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("voyage-3-large")))
                .withRequestBody(matchingJsonPath("$.input_type", equalTo("query")))
                .withRequestBody(matchingJsonPath("$.output_dimension", equalTo("4")))
                .withRequestBody(matchingJsonPath("$.truncation", equalTo("true"))));
    }

    @Test
    @DisplayName("os trechos vão em lotes, e a ordem da entrada é preservada")
    void shouldEmbedDocumentsInBatchesKeepingOrder() {
        // Lote de dois: três trechos viram duas chamadas. O provedor devolve fora de ordem de propósito.
        provider.stubFor(post(urlEqualTo(PATH))
                .inScenario("lotes")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(body(List.of(
                        indexed(1, new float[] {0.2f, 0.0f, 0.0f, 0.0f}),
                        indexed(0, new float[] {0.1f, 0.0f, 0.0f, 0.0f})))))
                .willSetStateTo("segundo"));
        provider.stubFor(post(urlEqualTo(PATH))
                .inScenario("lotes")
                .whenScenarioStateIs("segundo")
                .willReturn(okJson(body(List.of(indexed(0, new float[] {0.3f, 0.0f, 0.0f, 0.0f}))))));

        List<Embedding> embeddings = client.embedDocuments(List.of("primeiro", "segundo", "terceiro"));

        assertThat(embeddings).hasSize(3);
        assertThat(embeddings.get(0).toArray()[0]).isEqualTo(0.1f);
        assertThat(embeddings.get(1).toArray()[0]).isEqualTo(0.2f);
        assertThat(embeddings.get(2).toArray()[0]).isEqualTo(0.3f);
        provider.verify(2, postRequestedFor(urlEqualTo(PATH)));
        provider.verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.input_type", equalTo("document"))));
    }

    @Test
    @DisplayName("uma lista vazia não gera chamada nenhuma")
    void shouldNotCallProviderForEmptyInput() {
        assertThat(client.embedDocuments(List.of())).isEmpty();
        provider.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("sobrecarga do provedor (429) é repetida e, dando certo, a chamada conclui")
    void shouldRetryTransientFailures() {
        provider.stubFor(post(urlEqualTo(PATH))
                .inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withBody("{\"detail\":\"rate limit\"}"))
                .willSetStateTo("recuperado"));
        provider.stubFor(post(urlEqualTo(PATH))
                .inScenario("retry")
                .whenScenarioStateIs("recuperado")
                .willReturn(okJson(body(List.of(indexed(0, new float[] {1.0f, 0.0f, 0.0f, 0.0f}))))));

        assertThat(client.embedQuery("consulta").toArray()[0]).isEqualTo(1.0f);
        provider.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("um pedido recusado (400) não é repetido: tentar de novo não resolve")
    void shouldNotRetryRejectedRequests() {
        provider.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(400).withBody("{\"detail\":\"modelo inválido\"}")));

        assertThatExceptionOfType(EmbeddingRequestRejectedException.class)
                .isThrownBy(() -> client.embedQuery("consulta"))
                .withMessageContaining("modelo inválido");
        provider.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("um vetor de dimensão diferente da configurada é recusado, e não é repetido")
    void shouldRejectUnexpectedDimension() {
        stubSuccess(List.of(new float[] {0.1f, 0.2f}));

        assertThatExceptionOfType(EmbeddingRequestRejectedException.class)
                .isThrownBy(() -> client.embedQuery("consulta"))
                .withMessageContaining("dimensão 2");
        provider.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("uma resposta com menos vetores do que textos é recusada")
    void shouldRejectMissingVectors() {
        stubSuccess(List.of());

        assertThatExceptionOfType(EmbeddingUnavailableException.class)
                .isThrownBy(() -> client.embedQuery("consulta"))
                .withMessageContaining("0 vetores");
    }

    private static void stubSuccess(List<float[]> vectors) {
        List<String> items = new java.util.ArrayList<>();
        for (int i = 0; i < vectors.size(); i++) {
            items.add(indexed(i, vectors.get(i)));
        }
        provider.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(body(items))));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String body(List<String> items) {
        return """
                {"object": "list", "data": [%s], "model": "voyage-3-large", "usage": {"total_tokens": 42}}
                """.formatted(String.join(",", items));
    }

    private static String indexed(int index, float[] vector) {
        StringJoiner values = new StringJoiner(",", "[", "]");
        for (float value : vector) {
            values.add(Float.toString(value));
        }
        return """
                {"object": "embedding", "index": %d, "embedding": %s}
                """.formatted(index, values);
    }
}
