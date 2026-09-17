package com.lexflow.infrastructure.embedding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração do provedor de embeddings, lida de {@code lexflow.embeddings}.
 *
 * <p>O padrão é a API da Voyage AI, cujo formato de requisição — {@code input}, {@code model},
 * {@code input_type}, {@code output_dimension} — é o mesmo adotado pela maioria dos provedores. Trocar
 * de provedor compatível é só mudar {@code base-url}, {@code api-key} e {@code model}.
 *
 * <p>Timeout, retry e circuit breaker ficam em {@code resilience4j.*.instances.embeddings}.
 *
 * @param apiKey chave da API. Vazia não impede a aplicação de subir — só a indexação e a recuperação
 *     falham —, pelo mesmo motivo do cliente LLM: nem todo ambiente usa a base normativa
 * @param dimension dimensão pedida ao provedor. <strong>Precisa ser igual à da coluna {@code vector}
 *     da migration</strong>; mudá-la exige nova migration e reindexação de toda a base
 * @param batchSize quantos trechos vão em cada chamada; lotes grandes reduzem chamadas, mas cada
 *     chamada fica mais lenta e mais cara de repetir quando falha
 * @param truncateInput deixa o provedor cortar um texto acima do limite do modelo, em vez de recusar
 *     a chamada inteira. Um trecho normativo cortado ainda é útil; um lote recusado interrompe a
 *     indexação de toda a fonte
 */
@ConfigurationProperties(prefix = "lexflow.embeddings")
public record EmbeddingClientProperties(
        String baseUrl,
        String path,
        String apiKey,
        String model,
        Integer dimension,
        Integer batchSize,
        Duration connectTimeout,
        Duration responseTimeout,
        Boolean truncateInput) {

    public EmbeddingClientProperties {
        baseUrl = isBlank(baseUrl) ? "https://api.voyageai.com" : stripTrailingSlash(baseUrl.strip());
        path = isBlank(path) ? "/v1/embeddings" : path.strip();
        apiKey = isBlank(apiKey) ? null : apiKey.strip();
        model = isBlank(model) ? "voyage-3-large" : model.strip();
        dimension = dimension == null ? 1536 : dimension;
        batchSize = batchSize == null ? 32 : batchSize;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        responseTimeout = responseTimeout == null ? Duration.ofSeconds(60) : responseTimeout;
        truncateInput = truncateInput == null || truncateInput;
        if (dimension < 1) {
            throw new IllegalArgumentException("lexflow.embeddings.dimension deve ser positiva");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("lexflow.embeddings.batch-size deve ser positivo");
        }
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || responseTimeout.isNegative() || responseTimeout.isZero()) {
            throw new IllegalArgumentException("os timeouts de lexflow.embeddings devem ser positivos");
        }
    }

    public boolean hasApiKey() {
        return apiKey != null;
    }

    @Override
    public String toString() {
        return "EmbeddingClientProperties[baseUrl=%s, model=%s, dimension=%d, batchSize=%d, apiKey=%s]"
                .formatted(baseUrl, model, dimension, batchSize, hasApiKey() ? "***" : "(ausente)");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
