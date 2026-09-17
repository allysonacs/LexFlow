package com.lexflow.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lexflow.application.llm.LlmClientPort;
import com.lexflow.application.llm.LlmException;
import com.lexflow.application.llm.LlmRefusalException;
import com.lexflow.application.llm.LlmRequest;
import com.lexflow.application.llm.LlmRequestRejectedException;
import com.lexflow.application.llm.LlmResponse;
import com.lexflow.application.llm.LlmResponseValidationException;
import com.lexflow.application.llm.LlmStopReason;
import com.lexflow.application.llm.LlmUnavailableException;
import com.lexflow.application.llm.LlmUsage;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
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
 * Cliente da Messages API da Anthropic ({@code POST /v1/messages}), sobre {@link WebClient}.
 *
 * <p><strong>É um cliente HTTP genérico.</strong> Não sabe o que é uma demanda, uma pergunta jurídica
 * ou um trecho normativo: recebe um {@link LlmRequest} e devolve um {@link LlmResponse} validado.
 *
 * <p><strong>Proteções, de dentro para fora:</strong>
 *
 * <ol>
 *   <li><em>bulkhead</em>: limita as chamadas simultâneas, para que um provedor lento não prenda
 *       todas as threads do worker (seção 11);
 *   <li><em>time limiter</em>: corta cada tentativa que passar do tempo configurado;
 *   <li><em>circuit breaker</em>: com muitas falhas seguidas, para de chamar o provedor por um tempo
 *       e falha na hora, em vez de acumular esperas;
 *   <li><em>retry</em> com backoff exponencial: repete falhas transitórias (429, 5xx, 529, timeout,
 *       conexão).
 * </ol>
 *
 * <p>A configuração das quatro fica no {@code application.yml}, na instância {@value #RESILIENCE_INSTANCE}.
 * Falhas que não são do provedor — pedido recusado, resposta fora do schema, recusa do modelo — não
 * são repetidas nem contam para abrir o circuito.
 *
 * <p><strong>Nada de conteúdo no log.</strong> Em {@code INFO} saem só modelo, tokens, latência e
 * identificadores. Nem em {@code DEBUG} o prompt é registrado: apenas o tamanho e um hash, que
 * permitem correlacionar chamadas sem expor dados sensíveis (seção 12).
 */
public class AnthropicMessagesClient implements LlmClientPort {

    /** Nome da instância do Resilience4j usada por este cliente. */
    public static final String RESILIENCE_INSTANCE = "llm";

    static final String MESSAGES_PATH = "/v1/messages";

    private static final Logger log = LoggerFactory.getLogger(AnthropicMessagesClient.class);

    /** Status que indicam falha transitória do provedor. 529 é "sobrecarregado", específico da API. */
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 409, 429, 500, 502, 503, 504, 529);

    private final WebClient webClient;
    private final LlmClientProperties properties;
    private final JsonSchemaResponseValidator validator;
    private final ObjectMapper objectMapper;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final Bulkhead bulkhead;
    private final Clock clock;

    public AnthropicMessagesClient(
            WebClient webClient,
            LlmClientProperties properties,
            JsonSchemaResponseValidator validator,
            ObjectMapper objectMapper,
            Retry retry,
            CircuitBreaker circuitBreaker,
            TimeLimiter timeLimiter,
            Bulkhead bulkhead,
            Clock clock) {
        this.webClient = Objects.requireNonNull(webClient, "webClient não pode ser nulo");
        this.properties = Objects.requireNonNull(properties, "properties não pode ser nulo");
        this.validator = Objects.requireNonNull(validator, "validator não pode ser nulo");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper não pode ser nulo");
        this.retry = Objects.requireNonNull(retry, "retry não pode ser nulo");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker não pode ser nulo");
        this.timeLimiter = Objects.requireNonNull(timeLimiter, "timeLimiter não pode ser nulo");
        this.bulkhead = Objects.requireNonNull(bulkhead, "bulkhead não pode ser nulo");
        this.clock = Objects.requireNonNull(clock, "clock não pode ser nulo");
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Objects.requireNonNull(request, "request não pode ser nulo");
        if (!properties.hasApiKey()) {
            throw new LlmRequestRejectedException(
                    0, "configuration_error", "Chave da API do LLM não configurada (lexflow.llm.api-key)");
        }
        String model = request.model() != null ? request.model() : properties.defaultModel();
        ObjectNode body = buildBody(request, model);
        Instant startedAt = clock.instant();

        if (log.isDebugEnabled()) {
            log.debug(
                    "Chamando LLM: modelo={} estruturado={} prompt[tamanho={}, sha256={}]",
                    model,
                    request.expectsStructuredOutput(),
                    request.prompt().length(),
                    fingerprint(request.prompt()));
        }

        try {
            RawResponse raw = Mono.defer(() -> send(body))
                    .transformDeferred(BulkheadOperator.of(bulkhead))
                    .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                    .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                    .transformDeferred(RetryOperator.of(retry))
                    .block();
            LlmResponse response = interpret(request, model, Objects.requireNonNull(raw), startedAt);
            log.info(
                    "Chamada ao LLM concluída: modelo={} (pedido={}) stopReason={} tokens[entrada={}, saída={}, cache={}] latência={}ms requestId={}",
                    response.model(),
                    model,
                    response.stopReason(),
                    response.usage().inputTokens(),
                    response.usage().outputTokens(),
                    response.usage().cacheReadInputTokens(),
                    response.latency().toMillis(),
                    response.requestId());
            return response;
        } catch (RuntimeException e) {
            RuntimeException translated = translate(Exceptions.unwrap(e));
            log.warn(
                    "Chamada ao LLM falhou: modelo={} erro={} mensagem={} circuito={} duração={}ms",
                    model,
                    translated.getClass().getSimpleName(),
                    translated.getMessage(),
                    circuitBreaker.getState(),
                    Duration.between(startedAt, clock.instant()).toMillis());
            throw translated;
        }
    }

    /**
     * Uma tentativa: envia o corpo e traduz o status HTTP.
     *
     * <p>A tradução acontece aqui, dentro das proteções, para que o retry e o circuit breaker decidam
     * pelo tipo da exceção: {@link LlmUnavailableException} é falha do provedor;
     * {@link LlmRequestRejectedException}, não.
     */
    private Mono<RawResponse> send(ObjectNode body) {
        return webClient.post()
                .uri(MESSAGES_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    headers.set("x-api-key", properties.apiKey());
                    headers.set("anthropic-version", properties.anthropicVersion());
                    if (properties.refusalFallbackEnabled()) {
                        headers.set("anthropic-beta", LlmClientProperties.REFUSAL_FALLBACK_BETA);
                    }
                })
                .bodyValue(body)
                .exchangeToMono(this::toRawResponse)
                .onErrorMap(WebClientRequestException.class, e -> new LlmUnavailableException(
                        "Falha de comunicação com o provedor de LLM: " + e.getMostSpecificCause().getMessage(), e));
    }

    private Mono<RawResponse> toRawResponse(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        String requestId = response.headers().asHttpHeaders().getFirst("request-id");
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(payload -> {
                    if (status.is2xxSuccessful()) {
                        return Mono.just(new RawResponse(payload, requestId));
                    }
                    return Mono.error(httpError(status.value(), payload, requestId));
                });
    }

    private RuntimeException httpError(int status, String payload, String requestId) {
        String errorType = "unknown";
        String message = "sem detalhe";
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode error = root.path("error");
            errorType = error.path("type").asText(errorType);
            message = error.path("message").asText(message);
            if (requestId == null) {
                requestId = root.path("request_id").asText(null);
            }
        } catch (Exception e) {
            // Corpo fora do formato de erro da API (ex.: página de um proxy): fica o status.
        }
        String description = "Provedor de LLM respondeu %d (%s): %s [requestId=%s]"
                .formatted(status, errorType, message, requestId);
        if (RETRYABLE_STATUS.contains(status) || status >= 500) {
            return new LlmUnavailableException(description, status);
        }
        return new LlmRequestRejectedException(status, errorType, description);
    }

    private ObjectNode buildBody(LlmRequest request, String model) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", request.maxTokens() != null ? request.maxTokens() : properties.defaultMaxTokens());
        if (request.systemPrompt() != null) {
            body.put("system", request.systemPrompt());
        }
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", request.prompt());
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }

        ObjectNode outputConfig = objectMapper.createObjectNode();
        if (request.expectsStructuredOutput()) {
            outputConfig.putObject("format")
                    .put("type", "json_schema")
                    .set("schema", validator.parseSchema(request.outputSchema()));
        }
        if (request.effort() != null) {
            outputConfig.put("effort", request.effort().apiValue());
        }
        if (!outputConfig.isEmpty()) {
            body.set("output_config", outputConfig);
        }
        if (properties.refusalFallbackEnabled()) {
            body.put("fallbacks", properties.refusalFallback());
        }
        return body;
    }

    /** Converte a resposta HTTP bem-sucedida, aplicando as regras de parada e de schema. */
    private LlmResponse interpret(LlmRequest request, String requestedModel, RawResponse raw, Instant startedAt) {
        JsonNode json;
        try {
            json = objectMapper.readTree(raw.body());
        } catch (Exception e) {
            throw new LlmResponseValidationException(
                    "O provedor devolveu um corpo que não é JSON", requestedModel, List.of("corpo ilegível"));
        }
        String servedModel = json.path("model").asText(requestedModel);
        LlmStopReason stopReason = LlmStopReason.fromApiValue(json.path("stop_reason").asText(null));

        if (stopReason == LlmStopReason.REFUSAL) {
            JsonNode details = json.path("stop_details");
            throw new LlmRefusalException(servedModel, details.hasNonNull("category") ? details.get("category").asText() : null);
        }

        String text = extractText(json.path("content"));
        String structuredOutput = null;
        if (request.expectsStructuredOutput()) {
            if (stopReason == LlmStopReason.MAX_TOKENS) {
                throw new LlmResponseValidationException(
                        "A resposta estruturada foi cortada pelo limite de tokens", servedModel,
                        List.of("stop_reason=max_tokens"));
            }
            structuredOutput = validator.requireValid(request.outputSchema(), text, servedModel);
        }

        return new LlmResponse(
                json.path("id").asText(null),
                requestedModel,
                servedModel,
                text,
                structuredOutput,
                stopReason,
                usageOf(json.path("usage")),
                raw.requestId(),
                Duration.between(startedAt, clock.instant()));
    }

    /**
     * Junta os blocos de texto. Os demais tipos — raciocínio, marcações de recurso a outro modelo —
     * não fazem parte da resposta e são ignorados.
     */
    private static String extractText(JsonNode content) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText(""));
            }
        }
        return text.toString();
    }

    private static LlmUsage usageOf(JsonNode usage) {
        if (usage.isMissingNode() || usage.isNull()) {
            return LlmUsage.NONE;
        }
        return new LlmUsage(
                usage.path("input_tokens").asLong(0),
                usage.path("output_tokens").asLong(0),
                usage.path("cache_read_input_tokens").asLong(0),
                usage.path("cache_creation_input_tokens").asLong(0));
    }

    /** Converte as exceções das proteções nas exceções da porta. */
    private RuntimeException translate(Throwable error) {
        return switch (error) {
            case LlmException llmException -> llmException;
            case IllegalArgumentException invalidRequest -> invalidRequest;
            case CallNotPermittedException open -> new LlmUnavailableException(
                    "Circuito do provedor de LLM aberto: chamadas suspensas temporariamente", open);
            case TimeoutException timeout -> new LlmUnavailableException(
                    "Provedor de LLM não respondeu dentro do tempo limite", timeout);
            case BulkheadFullException full -> new LlmUnavailableException(
                    "Limite de chamadas simultâneas ao LLM atingido", full);
            default -> new LlmUnavailableException("Falha inesperada ao chamar o LLM: " + error.getMessage(), error);
        };
    }

    /** Hash curto do texto, para correlacionar chamadas no log sem expor o conteúdo. */
    private static String fingerprint(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return "indisponível";
        }
    }

    /** Corpo e identificador de uma resposta HTTP bem-sucedida. */
    private record RawResponse(String body, String requestId) {}
}
