package com.lexflow.application.legalcase;

import com.lexflow.domain.legalcase.LegalCaseStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Registro de uma transição de status, correspondente a uma linha de
 * {@code legal_case_status_history}.
 *
 * <p>É apenas a estrutura de dados a ser persistida: gravá-la é responsabilidade de quem implementa
 * {@link LegalCaseStatusHistoryRepository}, não do serviço de transição.
 *
 * @param previousStatus status anterior; nulo apenas no registro inicial, quando a demanda nasce
 * @param changedBy autor da mudança: um usuário identificado ou {@code SYSTEM} quando ela vem do
 *     próprio pipeline
 * @param reason motivo da mudança; opcional
 */
public record LegalCaseStatusHistoryEntry(
        UUID id,
        UUID legalCaseId,
        LegalCaseStatus previousStatus,
        LegalCaseStatus newStatus,
        Instant changedAt,
        String changedBy,
        String reason) {

    /** Autor usado quando a transição parte do próprio pipeline, sem ação humana. */
    public static final String SYSTEM_ACTOR = "SYSTEM";

    public LegalCaseStatusHistoryEntry {
        Objects.requireNonNull(id, "id não pode ser nulo");
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        Objects.requireNonNull(newStatus, "newStatus não pode ser nulo");
        Objects.requireNonNull(changedAt, "changedAt não pode ser nulo");
        if (changedBy == null || changedBy.isBlank()) {
            throw new IllegalArgumentException("changedBy é obrigatório: toda transição tem um autor");
        }
    }

    /** Indica se este é o registro que abre o histórico da demanda. */
    public boolean isInitial() {
        return previousStatus == null;
    }
}
