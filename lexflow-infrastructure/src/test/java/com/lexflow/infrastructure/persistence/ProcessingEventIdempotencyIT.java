package com.lexflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.infrastructure.persistence.entity.ProcessingEventEntity;
import com.lexflow.infrastructure.persistence.entity.ProcessingEventStatus;
import com.lexflow.infrastructure.persistence.repository.ProcessingEventJpaRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Garante que a idempotência do processamento assíncrono é sustentada pelo banco, e não por uma
 * verificação na aplicação: duas réplicas do worker que tentem registrar a mesma chave ao mesmo tempo
 * não conseguem gravar as duas linhas.
 */
class ProcessingEventIdempotencyIT extends AbstractPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-02-01T10:15:30Z");

    @Autowired
    private ProcessingEventJpaRepository processingEventRepository;

    private ProcessingEventEntity event(String idempotencyKey) {
        return new ProcessingEventEntity(
                UUID.randomUUID(),
                "LegalCaseReceivedEvent",
                UUID.randomUUID(),
                idempotencyKey,
                ProcessingEventStatus.IN_PROGRESS,
                "{\"legalCaseId\":\"%s\"}".formatted(UUID.randomUUID()),
                NOW,
                null);
    }

    @Test
    @DisplayName("a mesma idempotency_key não pode ser gravada duas vezes")
    void shouldRejectDuplicatedIdempotencyKey() {
        String idempotencyKey = "legal-case-received-" + UUID.randomUUID();
        processingEventRepository.saveAndFlush(event(idempotencyKey));

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> processingEventRepository.saveAndFlush(event(idempotencyKey)));
    }

    @Test
    @DisplayName("um evento é encontrado pela chave de idempotência, para poder ser ignorado no reprocessamento")
    void shouldFindEventByIdempotencyKey() {
        String idempotencyKey = "legal-case-received-" + UUID.randomUUID();
        ProcessingEventEntity saved = processingEventRepository.saveAndFlush(event(idempotencyKey));

        assertThat(processingEventRepository.existsByIdempotencyKey(idempotencyKey)).isTrue();
        assertThat(processingEventRepository.findByIdempotencyKey(idempotencyKey))
                .get()
                .satisfies(found -> {
                    assertThat(found.getId()).isEqualTo(saved.getId());
                    assertThat(found.getStatus()).isEqualTo(ProcessingEventStatus.IN_PROGRESS);
                    assertThat(found.getPayload()).contains("legalCaseId");
                    assertThat(found.getProcessedAt()).isNull();
                });
    }

    @Test
    @DisplayName("chaves diferentes convivem sem conflito")
    void shouldAcceptDifferentKeys() {
        processingEventRepository.saveAndFlush(event("chave-a-" + UUID.randomUUID()));
        processingEventRepository.saveAndFlush(event("chave-b-" + UUID.randomUUID()));

        assertThat(processingEventRepository.count()).isGreaterThanOrEqualTo(2);
    }
}
