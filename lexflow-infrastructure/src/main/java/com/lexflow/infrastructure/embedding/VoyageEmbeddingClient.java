package com.lexflow.infrastructure.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lexflow.application.knowledge.EmbeddingClientPort;
import com.lexflow.application.knowledge.EmbeddingException;
import com.lexflow.application.knowledge.EmbeddingRequestRejectedException;
import com.lexflow.application.knowledge.EmbeddingUnavailableException;
import com.lexflow.domain.knowledge.Embedding;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/**
 * Cliente do provedor de embeddings, sobre {@link WebClient} (Prompt 12, item 3).
 *
 * <p>Fala o formato da API da Voyage AI, que é o mesmo adotado pela maior parte dos provedores de
 * embeddings: {@code POST /v1/embeddings} com {@code input}, {@code model} e {@code input_type}.
 *
 * <p><strong>Documento e consulta não são a mesma coisa.</strong> O {@code input_type} informa ao
 * modelo se o texto é um trecho a ser indexado ou uma pergunta a ser respondida; os dois são
 * projetados em regiões próximas do espaço vetorial, e é isso que faz uma pergunta curta encontrar um
 * parágrafo longo de norma.
 *
 * <p><strong>Proteções</strong> idênticas às do cliente LLM, na instância {@value #RESILIENCE_INSTANCE}:
 * bulkhead, time limiter, circuit breaker e retry com backoff exponencial, configurados no
 * {@code application.yml} (seção 11).
 *
 * <p><strong>Nada de conteúdo no log</strong> (seção 12): saem a quantidade de textos, o tamanho total
 * e os tokens consumidos, nunca o texto.
 */
public class VoyageEmbeddingClient implements EmbeddingClientPort {

    /** Nome da instância do Resilience4j usada por este cliente. */
    public static final String RESILIENCE_INSTANCE = "embeddings";

    private static final Logger log = LoggerFactory.getLogger(VoyageEmbeddingClient.class);

    /** Status que indicam falha transitória do provedor. */
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 409, 429, 500, 502, 503, 504);

    private static final String DOCUMENT_INPUT_TYPE = "document";
    private static final String QUERY_INPUT_TYPE = "query";

    private final WebClient webClient;
    private final EmbeddingClientProperties properties;
    private final ObjectMapper objectMapper;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final Bulkhead bulkhead;

    public VoyageEmbeddingClient(
            WebClient webClient,
            EmbeddingClientProperties properties,
            ObjectMapper objectMapper,
            Retry retry,
            CircuitBreaker circuitBreaker,
            TimeLimiter timeLimiter,
            Bulkhead bulkhead) {
        this.webClient = Objects.requireNonNull(webClient, "webClient não pode ser nulo");
        this.properties = Objects.requireNonNull(properties, "properties não pode ser nulo");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper não pode ser nulo");
        this.retry = Objects.requireNonNull(retry, "retry não pode ser nulo");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker não pode ser nulo");
        this.timeLimiter = Objects.requireNonNull(timeLimiter, "timeLimiter não pode ser nulo");
        this.bulkhead = Objects.requireNonNull(bulkhead, "bulkhead não pode ser nulo");
    }

    @Override
    public int dimension() {
        return properties.dimension();
    }

    @Override
    public Embedding embedDocument(String text) {
        return embedAll(List.of(text), DOCUMENT_INPUT_TYPE).getFirst();
    }

    @Override
    public List<Embedding> embedDocuments(List<String> texts) {
        Objects.requireNonNull(texts, "texts não pode ser nulo");
        if (texts.isEmpty()) {
            return List.of();
        }
        List<Embedding> embeddings = new ArrayList<>(texts.size());
        // Em lotes: uma fonte normativa pode ter centenas de trechos, e o provedor limita o tamanho
        // de cada requisição.
        for (int start = 0; start < texts.size(); start += properties.batchSize()) {
            List<String> batch = texts.subList(start, Math.min(texts.size(), start + properties.batchSize()));
            embeddings.addAll(embedAll(batch, DOCUMENT_INPUT_TYPE));
        }
        return List.copyOf(embeddings);
    }

    @Override
    public Embedding embedQuery(String text) {
        return embedAll(List.of(text), QUERY_INPUT_TYPE).getFirst();
    }

    private List<Embedding> embedAll(List<String> texts, String inputType) {
        texts.forEach(text -> {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("não é possível gerar embedding de um texto vazio");
            }
        });
        if (!properties.hasApiKey()) {
            throw new EmbeddingRequestRejectedException(
                    0, "Chave da API de embeddings não configurada (lexflow.embeddings.api-key)");
        }

        ObjectNode body = buildBody(texts, inputType);
        long startedAt = System.nanoTime();
        try {
            String payload = Mono.defer(() -> send(body))
                    .transformDeferred(BulkheadOperator.of(bulkhead))
                    .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                    .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                    .transformDeferred(RetryOperator.of(retry))
                    .block();
            List<Embedding> embeddings = interpret(Objects.requireNonNull(payload), texts.size());
            log.info(
                    "Embeddings gerados: modelo={} tipo={} textos={} caracteres={} latência={}ms",
                    properties.model(),
                    inputType,
                    texts.size(),
                    texts.stream().mapToInt(String::length).sum(),
                    (System.nanoTime() - startedAt) / 1_000_000);
            return embeddings;
        } catch (RuntimeException e) {
            RuntimeException translated = translate(Exceptions.unwrap(e));
            log.warn(
                    "Falha ao gerar embeddings: modelo={} textos={} erro={} mensagem={} circuito={}",
                    properties.model(),
                    texts.size(),
                    translated.getClass().getSimpleName(),
                    translated.getMessage(),
                    circuitBreaker.getState());
            throw translated;
        }
    }

    private ObjectNode buildBody(List<String> texts, String inputType) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);
        body.put("model", properties.model());
        body.put("input_type", inputType);
        body.put("output_dimension", properties.dimension());
        body.put("truncation", properties.truncateInput());
        return body;
    }

    private Mono<String> send(ObjectNode body) {
        return webClient
                .post()
                .uri(properties.path())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(properties.apiKey()))
                .bodyValue(body)
                .exchangeToMono(this::toPayload)
                .onErrorMap(WebClientRequestException.class, e -> new EmbeddingUnavailableException(
                        "Falha de comunicação com o provedor de embeddings: " + e.getMostSpecificCause().getMessage(),
                        e));
    }

    private Mono<String> toPayload(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        return response.bodyToMono(String.class).defaultIfEmpty("").flatMap(payload -> status.is2xxSuccessful()
                ? Mono.just(payload)
                : Mono.error(httpError(status.value(), payload)));
    }

    private RuntimeException httpError(int status, String payload) {
        String message = "sem detalhe";
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode detail = root.hasNonNull("detail") ? root.get("detail") : root.path("error").path("message");
            message = detail.asText(message);
        } catch (Exception e) {
            // Corpo fora do formato de erro do provedor: fica o status.
        }
        String description = "Provedor de embeddings respondeu %d: %s".formatted(status, message);
        if (RETRYABLE_STATUS.contains(status) || status >= 500) {
            return new EmbeddingUnavailableException(description);
        }
        return new EmbeddingRequestRejectedException(status, description);
    }

    /** Lê os vetores da resposta, na ordem do índice informado pelo provedor. */
    private List<Embedding> interpret(String payload, int expectedCount) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new EmbeddingUnavailableException("O provedor de embeddings devolveu um corpo que não é JSON", e);
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() != expectedCount) {
            throw new EmbeddingUnavailableException(
                    "O provedor de embeddings devolveu %d vetores para %d textos".formatted(data.size(), expectedCount));
        }

        List<JsonNode> ordered = new ArrayList<>(data.size());
        data.forEach(ordered::add);
        // A API não garante a ordem dos itens; o índice de cada um é que amarra o vetor ao texto.
        ordered.sort(Comparator.comparingInt(node -> node.path("index").asInt(0)));

        List<Embedding> embeddings = new ArrayList<>(ordered.size());
        for (JsonNode node : ordered) {
            JsonNode vector = node.path("embedding");
            if (!vector.isArray() || vector.isEmpty()) {
                throw new EmbeddingUnavailableException("O provedor de embeddings devolveu um vetor vazio");
            }
            float[] values = new float[vector.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = (float) vector.get(i).asDouble();
            }
            Embedding embedding = Embedding.of(values);
            if (embedding.dimension() != properties.dimension()) {
                // Não é falha transitória: o modelo configurado não corresponde ao schema da base.
                // Repetir não resolveria, e um vetor de outra dimensão jamais entraria na coluna.
                throw new EmbeddingRequestRejectedException(
                        0,
                        "O provedor devolveu vetores de dimensão %d, mas a base normativa espera %d (modelo %s)"
                                .formatted(embedding.dimension(), properties.dimension(), properties.model()));
            }
            embeddings.add(embedding);
        }
        return List.copyOf(embeddings);
    }

    /** Converte as exceções das proteções nas exceções da porta. */
    private RuntimeException translate(Throwable error) {
        return switch (error) {
            case EmbeddingException embeddingException -> embeddingException;
            case IllegalArgumentException invalid -> invalid;
            case CallNotPermittedException open -> new EmbeddingUnavailableException(
                    "Circuito do provedor de embeddings aberto: chamadas suspensas temporariamente", open);
            case TimeoutException timeout -> new EmbeddingUnavailableException(
                    "Provedor de embeddings não respondeu dentro do tempo limite", timeout);
            case BulkheadFullException full -> new EmbeddingUnavailableException(
                    "Limite de chamadas simultâneas ao provedor de embeddings atingido", full);
            default -> new EmbeddingUnavailableException(
                    "Falha inesperada ao gerar embeddings: " + error.getMessage(), error);
        };
    }
}
