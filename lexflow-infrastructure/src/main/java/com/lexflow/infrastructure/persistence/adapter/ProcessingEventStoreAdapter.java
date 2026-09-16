package com.lexflow.infrastructure.persistence.adapter;

import com.lexflow.application.event.ProcessingEventStore;
import com.lexflow.application.event.ProcessingReservation;
import com.lexflow.infrastructure.persistence.entity.ProcessingEventEntity;
import com.lexflow.infrastructure.persistence.entity.ProcessingEventStatus;
import com.lexflow.infrastructure.persistence.repository.ProcessingEventJpaRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotência do consumo de fila apoiada na tabela {@code processing_events}.
 *
 * <p>Cada método roda em transação própria ({@link Propagation#REQUIRES_NEW}), de propósito: a
 * reserva precisa estar visível para as outras réplicas imediatamente, e o registro de falha precisa
 * sobreviver ao rollback da transação de processamento — se participasse dela, o rollback apagaria o
 * rastro da falha e a mensagem voltaria a parecer nunca tentada.
 */
@Component
public class ProcessingEventStoreAdapter implements ProcessingEventStore {

    private final ProcessingEventJpaRepository processingEventRepository;
    private final Clock clock;

    public ProcessingEventStoreAdapter(ProcessingEventJpaRepository processingEventRepository, Clock clock) {
        this.processingEventRepository = processingEventRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProcessingReservation reserve(String idempotencyKey, String eventType, UUID aggregateId) {
        Optional<ProcessingEventEntity> existing = processingEventRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return reserveExisting(existing.get());
        }

        try {
            processingEventRepository.saveAndFlush(new ProcessingEventEntity(
                    UUID.randomUUID(),
                    eventType,
                    aggregateId,
                    idempotencyKey,
                    ProcessingEventStatus.IN_PROGRESS,
                    // Só identificadores: o conteúdo dos documentos nunca sai da sua tabela (seção 12).
                    "{\"aggregateId\":\"%s\"}".formatted(aggregateId),
                    clock.instant(),
                    null));
            return ProcessingReservation.RESERVED;
        } catch (DataIntegrityViolationException e) {
            // Duas réplicas tentaram reservar ao mesmo tempo: o índice único decidiu, e quem perdeu
            // descarta a entrega.
            return ProcessingReservation.IN_PROGRESS_ELSEWHERE;
        }
    }

    /**
     * Decide o que fazer com um evento que já tem registro.
     *
     * <p>Uma tentativa anterior que falhou é retomada — é isso que dá sentido ao retry da mensagem.
     * Um evento já concluído, ou em andamento em outra réplica, faz a entrega ser descartada.
     */
    private ProcessingReservation reserveExisting(ProcessingEventEntity event) {
        return switch (event.getStatus()) {
            case PROCESSED -> ProcessingReservation.ALREADY_PROCESSED;
            case IN_PROGRESS -> ProcessingReservation.IN_PROGRESS_ELSEWHERE;
            case FAILED -> {
                event.markInProgress();
                processingEventRepository.save(event);
                yield ProcessingReservation.RESERVED;
            }
        };
    }

    @Override
    public void markProcessed(String idempotencyKey) {
        // Participa da transação de quem chamou: a demanda avançar e o evento ser concluído são a
        // mesma unidade de trabalho.
        ProcessingEventEntity event = require(idempotencyKey);
        event.markProcessed(clock.instant());
        processingEventRepository.save(event);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String idempotencyKey) {
        ProcessingEventEntity event = require(idempotencyKey);
        event.markFailed();
        processingEventRepository.save(event);
    }

    private ProcessingEventEntity require(String idempotencyKey) {
        return processingEventRepository
                .findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "evento '%s' não foi reservado antes de ter o desfecho registrado".formatted(idempotencyKey)));
    }
}
