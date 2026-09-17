package com.lexflow.application.review;

import com.lexflow.domain.decision.DecisionType;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Evento emitido quando um responsável humano registra a decisão de uma demanda (Prompt 15, item 5).
 *
 * <p>Carrega identificadores e metadados, nunca o conteúdo de documentos nem o comentário da decisão
 * (seção 12): quem consumir o evento e precisar do texto o busca pela demanda.
 *
 * @param resultingStatus status para o qual a demanda foi levada pela decisão
 * @param idempotencyKey derivada do próprio evento, como na ingestão, para o controle de idempotência
 *     da fila ser independente do controle da API
 */
public record DecisionRegisteredEvent(
        UUID eventId,
        UUID decisionId,
        UUID legalCaseId,
        LegalCaseType caseType,
        DecisionType decisionType,
        LegalCaseStatus resultingStatus,
        String decidedBy,
        String idempotencyKey,
        Instant occurredAt) {

    /** Nome do tipo de evento, usado como {@code event_type} e na chave de roteamento. */
    public static final String EVENT_TYPE = "DECISION_REGISTERED";

    public DecisionRegisteredEvent {
        Objects.requireNonNull(eventId, "eventId não pode ser nulo");
        Objects.requireNonNull(decisionId, "decisionId não pode ser nulo");
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        Objects.requireNonNull(caseType, "caseType não pode ser nulo");
        Objects.requireNonNull(decisionType, "decisionType não pode ser nulo");
        Objects.requireNonNull(resultingStatus, "resultingStatus não pode ser nulo");
        Objects.requireNonNull(occurredAt, "occurredAt não pode ser nulo");
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new IllegalArgumentException("decidedBy é obrigatório");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey é obrigatória");
        }
    }

    /** Monta o evento com a chave derivada do próprio evento. */
    public static DecisionRegisteredEvent of(
            UUID eventId,
            UUID decisionId,
            UUID legalCaseId,
            LegalCaseType caseType,
            DecisionType decisionType,
            String decidedBy,
            Instant occurredAt) {
        return new DecisionRegisteredEvent(
                eventId,
                decisionId,
                legalCaseId,
                caseType,
                decisionType,
                decisionType.resultingStatus(),
                decidedBy,
                "%s:%s".formatted(EVENT_TYPE, eventId),
                occurredAt);
    }
}
