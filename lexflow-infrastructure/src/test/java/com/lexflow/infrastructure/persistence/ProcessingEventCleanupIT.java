package com.lexflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.lexflow.infrastructure.idempotency.IdempotencyRetentionProperties;
import com.lexflow.infrastructure.idempotency.ProcessingEventCleanupJob;
import com.lexflow.infrastructure.persistence.entity.ProcessingEventEntity;
import com.lexflow.infrastructure.persistence.entity.ProcessingEventStatus;
import com.lexflow.infrastructure.persistence.repository.ProcessingEventJpaRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Expiração das chaves de idempotência (Prompt 17, item 2).
 *
 * <p>Sem esta limpeza, {@code processing_events} cresce para sempre: ela ganha uma linha por
 * ingestão, por decisão e por mensagem consumida, e nenhuma delas é apagada pelo fluxo normal.
 */
class ProcessingEventCleanupIT extends AbstractPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    @Autowired
    private ProcessingEventJpaRepository repository;

    @Autowired
    private EntityManager entityManager;

    private ProcessingEventCleanupJob job;

    @BeforeEach
    void setUp() {
        job = new ProcessingEventCleanupJob(
                repository,
                new IdempotencyRetentionProperties(Duration.ofDays(30), true, Duration.ofHours(1), 2),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("chaves processadas e antigas são removidas; as recentes continuam reconhecidas")
    void shouldRemoveOnlyExpiredProcessedKeys() {
        UUID antiga = save("antiga-1", ProcessingEventStatus.PROCESSED, NOW.minus(Duration.ofDays(31)));
        UUID tambemAntiga = save("antiga-2", ProcessingEventStatus.PROCESSED, NOW.minus(Duration.ofDays(60)));
        UUID recente = save("recente", ProcessingEventStatus.PROCESSED, NOW.minus(Duration.ofDays(2)));
        entityManager.flush();

        int removidas = job.removeExpired();
        entityManager.flush();
        entityManager.clear();

        assertThat(removidas).isEqualTo(2);
        assertThat(repository.findById(antiga)).isEmpty();
        assertThat(repository.findById(tambemAntiga)).isEmpty();
        // Enquanto a chave existe, um reenvio é reconhecido como repetição.
        assertThat(repository.findById(recente)).isPresent();
    }

    @Test
    @DisplayName("eventos falhos e em andamento não são removidos, por mais antigos que sejam")
    void shouldKeepFailedAndInProgressEvents() {
        UUID falho = save("falho", ProcessingEventStatus.FAILED, NOW.minus(Duration.ofDays(90)));
        UUID emAndamento = save("em-andamento", ProcessingEventStatus.IN_PROGRESS, NOW.minus(Duration.ofDays(90)));
        entityManager.flush();

        int removidas = job.removeExpired();
        entityManager.flush();
        entityManager.clear();

        assertThat(removidas).isZero();
        // Um evento falho é evidência de um problema; um em andamento pode estar rodando em outra réplica.
        assertThat(repository.findById(falho)).isPresent();
        assertThat(repository.findById(emAndamento)).isPresent();
    }

    /** O tamanho do lote é 2 neste teste: cinco linhas exigem mais de uma passada. */
    @Test
    @DisplayName("a remoção acontece em lotes, sem deixar sobras")
    void shouldRemoveInBatches() {
        for (int index = 0; index < 5; index++) {
            save("lote-" + index, ProcessingEventStatus.PROCESSED, NOW.minus(Duration.ofDays(40)));
        }
        entityManager.flush();

        assertThat(job.removeExpired()).isEqualTo(5);
    }

    private UUID save(String key, ProcessingEventStatus status, Instant processedAt) {
        ProcessingEventEntity event = new ProcessingEventEntity(
                UUID.randomUUID(),
                "LEGAL_CASE_RECEIVED",
                UUID.randomUUID(),
                key,
                status,
                "{}",
                processedAt,
                status == ProcessingEventStatus.PROCESSED ? processedAt : null);
        return repository.saveAndFlush(event).getId();
    }
}
