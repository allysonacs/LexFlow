package com.lexflow.infrastructure.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração do cliente LLM, lida de {@code lexflow.llm}.
 *
 * <p>Timeout, retry e circuit breaker ficam em {@code resilience4j.*.instances.llm}, no formato
 * padrão do Resilience4j; aqui fica o que é do provedor.
 *
 * @param baseUrl endereço da API; trocado nos testes por um dublê
 * @param apiKey chave da API. Vazia não impede a aplicação de subir — só as chamadas falham —, porque
 *     nem todo ambiente usa o LLM
 * @param anthropicVersion valor do cabeçalho {@code anthropic-version}
 * @param defaultModel modelo usado quando o pedido não informa outro
 * @param defaultMaxTokens limite de tokens quando o pedido não informa outro. As chamadas não usam
 *     streaming, e um valor muito alto arrisca estourar o timeout
 * @param connectTimeout tempo máximo para abrir a conexão
 * @param responseTimeout tempo máximo de espera pela resposta HTTP, por tentativa. Deve ser um pouco
 *     maior que o {@code timeout-duration} do time limiter, que é quem normalmente corta a chamada
 * @param refusalFallback modo de recurso do provedor quando o modelo recusa um pedido por política:
 *     {@code default} (o padrão) deixa o provedor escolher outro modelo; vazio desliga o recurso
 */
@ConfigurationProperties(prefix = "lexflow.llm")
public record LlmClientProperties(
        String baseUrl,
        String apiKey,
        String anthropicVersion,
        String defaultModel,
        Integer defaultMaxTokens,
        Duration connectTimeout,
        Duration responseTimeout,
        String refusalFallback) {

    /** Beta exigido pelo parâmetro {@code fallbacks: "default"}. */
    public static final String REFUSAL_FALLBACK_BETA = "server-side-fallback-2026-07-01";

    public LlmClientProperties {
        baseUrl = isBlank(baseUrl) ? "https://api.anthropic.com" : stripTrailingSlash(baseUrl.strip());
        apiKey = isBlank(apiKey) ? null : apiKey.strip();
        anthropicVersion = isBlank(anthropicVersion) ? "2023-06-01" : anthropicVersion.strip();
        defaultModel = isBlank(defaultModel) ? "claude-opus-5" : defaultModel.strip();
        defaultMaxTokens = defaultMaxTokens == null ? 16_000 : defaultMaxTokens;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        responseTimeout = responseTimeout == null ? Duration.ofMinutes(3) : responseTimeout;
        // Ausente liga o recurso no modo padrão; vazio, informado de propósito, desliga.
        refusalFallback = refusalFallback == null ? "default" : (refusalFallback.isBlank() ? null : refusalFallback.strip());
        if (defaultMaxTokens < 1) {
            throw new IllegalArgumentException("lexflow.llm.default-max-tokens deve ser positivo");
        }
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || responseTimeout.isNegative() || responseTimeout.isZero()) {
            throw new IllegalArgumentException("os timeouts de lexflow.llm devem ser positivos");
        }
    }

    public boolean hasApiKey() {
        return apiKey != null;
    }

    public boolean refusalFallbackEnabled() {
        return refusalFallback != null;
    }

    @Override
    public String toString() {
        return "LlmClientProperties[baseUrl=%s, apiKey=%s, defaultModel=%s, defaultMaxTokens=%d]"
                .formatted(baseUrl, hasApiKey() ? "***" : "(ausente)", defaultModel, defaultMaxTokens);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
