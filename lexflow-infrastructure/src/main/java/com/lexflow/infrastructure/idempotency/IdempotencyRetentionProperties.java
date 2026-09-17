package com.lexflow.infrastructure.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Política de retenção das chaves de idempotência, lida de {@code lexflow.idempotency}.
 *
 * @param retention por quanto tempo uma chave já processada continua sendo reconhecida como
 *     repetição. <strong>Precisa ser maior do que a janela de novas tentativas de qualquer
 *     cliente</strong>: apagada a chave, um reenvio muito atrasado voltaria a criar a demanda
 * @param cleanupEnabled desligar é útil quando a limpeza é feita fora da aplicação
 * @param cleanupInterval intervalo entre execuções da limpeza
 * @param cleanupBatchSize linhas apagadas por lote, para a limpeza não segurar um bloqueio longo
 */
@ConfigurationProperties(prefix = "lexflow.idempotency")
public record IdempotencyRetentionProperties(
        Duration retention, Boolean cleanupEnabled, Duration cleanupInterval, Integer cleanupBatchSize) {

    public IdempotencyRetentionProperties {
        retention = retention == null ? Duration.ofDays(30) : retention;
        cleanupEnabled = cleanupEnabled == null || cleanupEnabled;
        cleanupInterval = cleanupInterval == null ? Duration.ofHours(1) : cleanupInterval;
        cleanupBatchSize = cleanupBatchSize == null ? 1_000 : cleanupBatchSize;
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("lexflow.idempotency.retention deve ser positiva");
        }
        if (cleanupInterval.isNegative() || cleanupInterval.isZero()) {
            throw new IllegalArgumentException("lexflow.idempotency.cleanup.interval deve ser positivo");
        }
        if (cleanupBatchSize < 1) {
            throw new IllegalArgumentException("lexflow.idempotency.cleanup.batch-size deve ser positivo");
        }
    }
}
