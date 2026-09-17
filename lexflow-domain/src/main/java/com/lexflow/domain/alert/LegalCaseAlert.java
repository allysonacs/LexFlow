package com.lexflow.domain.alert;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Alerta registrado em uma demanda, correspondente a uma linha de {@code legal_case_alerts}.
 *
 * <p>A mensagem descreve o problema com metadados — tipo, modelo, violações do schema —, nunca com o
 * conteúdo do documento (seção 12).
 *
 * @param documentId documento que originou o alerta; nulo quando o alerta é da demanda inteira
 * @param resolvedAt momento em que um humano tratou o alerta; nulo enquanto ele está em aberto
 */
public record LegalCaseAlert(
        UUID id,
        UUID legalCaseId,
        UUID documentId,
        LegalCaseAlertType type,
        String message,
        Instant createdAt,
        Instant resolvedAt) {

    /** Tamanho máximo da mensagem, compatível com a coluna. */
    public static final int MESSAGE_MAX_LENGTH = 2000;

    public LegalCaseAlert {
        Objects.requireNonNull(id, "id não pode ser nulo");
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        Objects.requireNonNull(type, "type não pode ser nulo");
        Objects.requireNonNull(createdAt, "createdAt não pode ser nulo");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message é obrigatória");
        }
        message = message.strip();
        if (message.length() > MESSAGE_MAX_LENGTH) {
            message = message.substring(0, MESSAGE_MAX_LENGTH);
        }
        if (resolvedAt != null && resolvedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("resolvedAt não pode ser anterior a createdAt");
        }
    }

    /** Abre um alerta. */
    public static LegalCaseAlert open(
            UUID id, UUID legalCaseId, UUID documentId, LegalCaseAlertType type, String message, Instant createdAt) {
        return new LegalCaseAlert(id, legalCaseId, documentId, type, message, createdAt, null);
    }

    public boolean isOpen() {
        return resolvedAt == null;
    }

    /** Indica se o alerta impede a demanda de avançar: todo alerta em aberto impede. */
    public boolean blocksPipeline() {
        return isOpen();
    }
}
