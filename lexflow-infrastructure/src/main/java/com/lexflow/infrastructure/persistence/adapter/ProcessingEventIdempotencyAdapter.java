package com.lexflow.infrastructure.persistence.adapter;

import com.lexflow.application.idempotency.IdempotencyNamespace;
import com.lexflow.application.idempotency.IdempotentOperation;
import com.lexflow.application.idempotency.IdempotentOperationStore;
import com.lexflow.infrastructure.persistence.entity.ProcessingEventEntity;
import com.lexflow.infrastructure.persistence.entity.ProcessingEventStatus;
import com.lexflow.infrastructure.persistence.repository.ProcessingEventJpaRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Idempotência dos endpoints de escrita apoiada na tabela {@code processing_events}.
 *
 * <p>Reaproveitar a tabela do processamento assíncrono é proposital: a garantia que a seção 11 exige
 * é a mesma em todos os casos — um índice único sobre a chave —, e duplicar a estrutura só criaria
 * vários lugares para manter. Como o índice é global, a chave do cliente é gravada com o prefixo do
 * {@link IdempotencyNamespace}, para que a mesma chave usada em dois endpoints diferentes, ou por uma
 * mensagem de fila, nunca seja confundida com uma repetição.
 */
@Component
public class ProcessingEventIdempotencyAdapter implements IdempotentOperationStore {

    private final ProcessingEventJpaRepository processingEventRepository;
    private final Clock clock;

    public ProcessingEventIdempotencyAdapter(ProcessingEventJpaRepository processingEventRepository, Clock clock) {
        this.processingEventRepository = processingEventRepository;
        this.clock = clock;
    }

    @Override
    public Optional<IdempotentOperation> reserve(
            IdempotencyNamespace namespace, String idempotencyKey, UUID aggregateId) {
        String storedKey = namespace.storedKey(idempotencyKey);

        Optional<ProcessingEventEntity> existing = processingEventRepository.findByIdempotencyKey(storedKey);
        if (existing.isPresent()) {
            return existing.map(entity -> toOperation(namespace, idempotencyKey, entity));
        }

        try {
            processingEventRepository.saveAndFlush(new ProcessingEventEntity(
                    UUID.randomUUID(),
                    namespace.eventType(),
                    aggregateId,
                    storedKey,
                    ProcessingEventStatus.IN_PROGRESS,
                    // Só o identificador do agregado: o conteúdo dos documentos nunca é registrado
                    // fora da sua tabela (seção 12).
                    "{\"aggregateId\":\"%s\"}".formatted(aggregateId),
                    clock.instant(),
                    null));
            return Optional.empty();
        } catch (DataIntegrityViolationException e) {
            // Duas requisições com a mesma chave ao mesmo tempo: o índice único decide qual delas
            // executa a operação, e a perdedora passa a devolver o resultado da vencedora.
            return processingEventRepository
                    .findByIdempotencyKey(storedKey)
                    .map(entity -> toOperation(namespace, idempotencyKey, entity));
        }
    }

    @Override
    public void markCompleted(IdempotencyNamespace namespace, String idempotencyKey) {
        String storedKey = namespace.storedKey(idempotencyKey);
        ProcessingEventEntity event = processingEventRepository
                .findByIdempotencyKey(storedKey)
                .orElseThrow(() -> new IllegalStateException(
                        "chave de idempotência '%s' não foi reservada antes de ser concluída".formatted(idempotencyKey)));
        event.markProcessed(clock.instant());
        processingEventRepository.save(event);
    }

    private IdempotentOperation toOperation(
            IdempotencyNamespace namespace, String idempotencyKey, ProcessingEventEntity entity) {
        return new IdempotentOperation(
                namespace,
                idempotencyKey,
                entity.getAggregateId(),
                entity.getStatus() == ProcessingEventStatus.PROCESSED);
    }
}
