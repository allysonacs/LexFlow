package com.lexflow.domain.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Uma linha da trilha de auditoria (seção 6).
 *
 * <p><strong>Append-only.</strong> Não existe método que altere uma linha: nem no domínio, nem no
 * repositório, nem no banco — a migration V9 instala um gatilho que recusa {@code UPDATE} e
 * {@code DELETE}. Uma trilha que pode ser corrigida depois não é trilha.
 *
 * <p>O {@code payload} guarda o que mudou, sempre em metadados: nome de status, tipo de decisão,
 * identificadores. Nunca conteúdo de documento nem texto de prompt (seção 12).
 *
 * @param legalCaseId demanda a que a linha pertence, para a linha do tempo poder ser reconstruída
 *     mesmo quando a entidade auditada é outra (uma resposta, uma decisão)
 * @param actor quem executou a ação: um usuário identificado, {@code SYSTEM} ou {@code AI}
 */
public record AuditLog(
        UUID id,
        AuditedEntity entityType,
        UUID entityId,
        UUID legalCaseId,
        AuditAction action,
        String actor,
        Map<String, String> payload,
        Instant occurredAt) {

    /** Autor usado quando a ação parte do próprio pipeline. */
    public static final String SYSTEM_ACTOR = "SYSTEM";

    /** Autor usado quando a ação é consequência direta de uma resposta do modelo. */
    public static final String AI_ACTOR = "AI";

    public AuditLog {
        Objects.requireNonNull(id, "id não pode ser nulo");
        Objects.requireNonNull(entityType, "entityType não pode ser nulo");
        Objects.requireNonNull(entityId, "entityId não pode ser nulo");
        Objects.requireNonNull(action, "action não pode ser nulo");
        Objects.requireNonNull(occurredAt, "occurredAt não pode ser nulo");
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor é obrigatório: toda ação auditada tem um autor");
        }
        actor = actor.strip();
        payload = payload == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(payload));
    }
}
