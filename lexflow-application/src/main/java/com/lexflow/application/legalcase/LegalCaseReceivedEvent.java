package com.lexflow.application.legalcase;

import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Evento emitido quando uma demanda é recebida e fica pronta para o processamento assíncrono.
 *
 * <p>Carrega apenas identificadores e metadados — nunca o conteúdo dos documentos, conforme a seção
 * 12 da base de conhecimento. O consumidor busca o que precisar a partir do {@code legalCaseId}.
 *
 * @param idempotencyKey chave que o consumidor grava em {@code processing_events} antes de processar
 *     (seção 11); é derivada do evento, e não da chave enviada pelo cliente, para que o controle de
 *     idempotência da fila seja independente do controle da API
 */
public record LegalCaseReceivedEvent(
        UUID eventId,
        UUID legalCaseId,
        LegalCaseType caseType,
        CasePriority priority,
        int documentCount,
        String idempotencyKey,
        Instant occurredAt) {

    /** Nome do tipo de evento, usado como {@code event_type} e como tópico no Prompt 07. */
    public static final String EVENT_TYPE = "LEGAL_CASE_RECEIVED";

    public LegalCaseReceivedEvent {
        Objects.requireNonNull(eventId, "eventId não pode ser nulo");
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        Objects.requireNonNull(caseType, "caseType não pode ser nulo");
        Objects.requireNonNull(priority, "priority não pode ser nulo");
        Objects.requireNonNull(occurredAt, "occurredAt não pode ser nulo");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey é obrigatória: todo evento precisa poder ser reprocessado");
        }
    }

    /** Monta o evento de uma demanda recém-recebida, com a chave derivada do próprio evento. */
    public static LegalCaseReceivedEvent of(
            UUID eventId, UUID legalCaseId, LegalCaseType caseType, CasePriority priority, int documentCount, Instant occurredAt) {
        return new LegalCaseReceivedEvent(
                eventId,
                legalCaseId,
                caseType,
                priority,
                documentCount,
                "%s:%s".formatted(EVENT_TYPE, eventId),
                occurredAt);
    }
}
