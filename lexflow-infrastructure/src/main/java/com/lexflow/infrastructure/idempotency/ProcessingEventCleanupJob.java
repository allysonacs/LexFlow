package com.lexflow.infrastructure.idempotency;

import com.lexflow.infrastructure.persistence.repository.ProcessingEventJpaRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expira as chaves de idempotência já processadas (Prompt 17, item 2).
 *
 * <p>Sem isso, {@code processing_events} cresce para sempre: ela ganha uma linha por ingestão, por
 * decisão e por mensagem consumida, e nenhuma delas é apagada pelo fluxo normal.
 *
 * <p><strong>A retenção é um compromisso, não um detalhe.</strong> Enquanto a chave existe, um
 * reenvio é reconhecido como repetição; depois de apagada, o mesmo reenvio criaria uma demanda nova.
 * Por isso o padrão é de 30 dias — bem acima da janela de novas tentativas de qualquer cliente
 * razoável — e mudá-lo para menos é uma decisão consciente sobre esse risco.
 *
 * <p>Só linhas {@code PROCESSED} são removidas: um evento {@code FAILED} continua sendo evidência de
 * um problema, e um {@code IN_PROGRESS} pode estar em execução em outra réplica.
 *
 * <p>A limpeza roda em lotes e nunca propaga exceção: ela é manutenção, e uma falha aqui não pode
 * derrubar a aplicação nem aparecer como incidente de negócio.
 */
@Component
@EnableConfigurationProperties(IdempotencyRetentionProperties.class)
@ConditionalOnProperty(name = "lexflow.idempotency.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class ProcessingEventCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ProcessingEventCleanupJob.class);

    /** Teto de lotes por execução, para uma tabela muito grande não prender o job indefinidamente. */
    private static final int MAX_BATCHES_PER_RUN = 50;

    private final ProcessingEventJpaRepository repository;
    private final IdempotencyRetentionProperties properties;
    private final Clock clock;

    public ProcessingEventCleanupJob(
            ProcessingEventJpaRepository repository, IdempotencyRetentionProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** Executa a limpeza no intervalo configurado, com atraso inicial para não competir com a subida. */
    @Scheduled(
            fixedDelayString = "${lexflow.idempotency.cleanup.interval:1h}",
            initialDelayString = "${lexflow.idempotency.cleanup.initial-delay:5m}")
    public void cleanUp() {
        try {
            int removed = removeExpired();
            if (removed > 0) {
                log.info(
                        "Chaves de idempotência expiradas removidas: {} (retenção de {})",
                        removed,
                        properties.retention());
            }
        } catch (RuntimeException e) {
            // Manutenção não derruba nada: a tabela cresce um pouco mais até a próxima execução.
            log.error("Falha ao expirar chaves de idempotência", e);
        }
    }

    /**
     * Remove as chaves expiradas, em lotes.
     *
     * @return quantas linhas foram removidas
     */
    @Transactional
    public int removeExpired() {
        Instant cutoff = clock.instant().minus(properties.retention());
        int removed = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = repository.deleteProcessedBefore(cutoff, properties.cleanupBatchSize());
            removed += deleted;
            if (deleted < properties.cleanupBatchSize()) {
                break;
            }
        }
        return removed;
    }
}
