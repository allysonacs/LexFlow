package com.lexflow.infrastructure.persistence.adapter;

import com.lexflow.application.legalcase.IdempotentIngestion;
import com.lexflow.application.legalcase.LegalCaseIngestionIdempotencyStore;
import com.lexflow.infrastructure.persistence.entity.ProcessingEventEntity;
import com.lexflow.infrastructure.persistence.entity.ProcessingEventStatus;
import com.lexflow.infrastructure.persistence.repository.ProcessingEventJpaRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Idempotência da ingestão apoiada na tabela {@code processing_events}.
 *
 * <p>Reaproveitar a tabela do processamento assíncrono é proposital: a garantia que a seção 11 exige
 * é a mesma nos dois casos — um índice único sobre a chave —, e duplicar a estrutura só criaria dois
 * lugares para manter. Como o índice é global, a chave do cliente é gravada com um prefixo de
 * namespace, para que uma chave escolhida por quem chama a API nunca colida com a chave de uma
 * mensagem de fila.
 */
@Component
public class LegalCaseIngestionIdempotencyAdapter implements LegalCaseIngestionIdempotencyStore {

    /** Tipo gravado em {@code event_type}, que identifica a origem do registro. */
    static final String EVENT_TYPE = "LEGAL_CASE_INGESTION_REQUEST";

    /** Prefixo que isola as chaves vindas do cliente das chaves geradas pelo pipeline. */
    static final String KEY_NAMESPACE = "legal-case-ingestion:";

    private final ProcessingEventJpaRepository processingEventRepository;
    private final Clock clock;

    public LegalCaseIngestionIdempotencyAdapter(ProcessingEventJpaRepository processingEventRepository, Clock clock) {
        this.processingEventRepository = processingEventRepository;
        this.clock = clock;
    }

    @Override
    public Optional<IdempotentIngestion> reserve(String idempotencyKey, UUID legalCaseId) {
        String storedKey = storedKey(idempotencyKey);

        Optional<ProcessingEventEntity> existing = processingEventRepository.findByIdempotencyKey(storedKey);
        if (existing.isPresent()) {
            return existing.map(entity -> toIngestion(idempotencyKey, entity));
        }

        try {
            processingEventRepository.saveAndFlush(new ProcessingEventEntity(
                    UUID.randomUUID(),
                    EVENT_TYPE,
                    legalCaseId,
                    storedKey,
                    ProcessingEventStatus.IN_PROGRESS,
                    // Só o identificador da demanda: o conteúdo dos documentos nunca é registrado
                    // fora da sua tabela (seção 12).
                    "{\"legalCaseId\":\"%s\"}".formatted(legalCaseId),
                    clock.instant(),
                    null));
            return Optional.empty();
        } catch (DataIntegrityViolationException e) {
            // Duas requisições com a mesma chave ao mesmo tempo: o índice único decide qual delas
            // cria a demanda, e a perdedora passa a devolver o resultado da vencedora.
            return processingEventRepository
                    .findByIdempotencyKey(storedKey)
                    .map(entity -> toIngestion(idempotencyKey, entity));
        }
    }

    @Override
    public void markCompleted(String idempotencyKey) {
        String storedKey = storedKey(idempotencyKey);
        ProcessingEventEntity event = processingEventRepository
                .findByIdempotencyKey(storedKey)
                .orElseThrow(() -> new IllegalStateException(
                        "chave de idempotência '%s' não foi reservada antes de ser concluída".formatted(idempotencyKey)));
        event.markProcessed(clock.instant());
        processingEventRepository.save(event);
    }

    private IdempotentIngestion toIngestion(String idempotencyKey, ProcessingEventEntity entity) {
        return new IdempotentIngestion(
                idempotencyKey, entity.getAggregateId(), entity.getStatus() == ProcessingEventStatus.PROCESSED);
    }

    private String storedKey(String idempotencyKey) {
        return KEY_NAMESPACE + idempotencyKey;
    }
}
