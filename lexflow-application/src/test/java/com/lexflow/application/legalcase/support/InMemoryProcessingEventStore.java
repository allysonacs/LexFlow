package com.lexflow.application.legalcase.support;

import com.lexflow.application.event.ProcessingEventStore;
import com.lexflow.application.event.ProcessingReservation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Controle de idempotência em memória, com a mesma semântica do adapter sobre
 * {@code processing_events}: um evento concluído nunca mais é reservado, um que falhou pode ser
 * retomado.
 */
public final class InMemoryProcessingEventStore implements ProcessingEventStore {

    /** Situação de um evento, espelhando o que a tabela guarda. */
    public enum State {
        IN_PROGRESS,
        PROCESSED,
        FAILED
    }

    private final Map<String, State> events = new LinkedHashMap<>();

    @Override
    public ProcessingReservation reserve(String idempotencyKey, String eventType, UUID aggregateId) {
        State state = events.get(idempotencyKey);
        if (state == State.PROCESSED) {
            return ProcessingReservation.ALREADY_PROCESSED;
        }
        if (state == State.IN_PROGRESS) {
            return ProcessingReservation.IN_PROGRESS_ELSEWHERE;
        }
        events.put(idempotencyKey, State.IN_PROGRESS);
        return ProcessingReservation.RESERVED;
    }

    @Override
    public void markProcessed(String idempotencyKey) {
        events.put(idempotencyKey, State.PROCESSED);
    }

    @Override
    public void markFailed(String idempotencyKey) {
        events.put(idempotencyKey, State.FAILED);
    }

    /** Situação atual de um evento, para as asserções dos testes. */
    public State stateOf(String idempotencyKey) {
        return events.get(idempotencyKey);
    }

    /** Força o estado de um evento, para montar cenários de reentrega. */
    public void put(String idempotencyKey, State state) {
        events.put(idempotencyKey, state);
    }

    public int size() {
        return events.size();
    }
}
