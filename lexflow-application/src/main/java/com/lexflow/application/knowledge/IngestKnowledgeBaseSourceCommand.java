package com.lexflow.application.knowledge;

import com.lexflow.domain.knowledge.KnowledgeBaseSourceType;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Pedido de indexação de uma fonte normativa.
 *
 * @param text texto integral da fonte, já extraído do arquivo quando o envio foi por upload
 * @param idempotencyKey opcional; com ela, reenviar a mesma norma não a indexa duas vezes
 */
public record IngestKnowledgeBaseSourceCommand(
        String title,
        KnowledgeBaseSourceType sourceType,
        LocalDate effectiveDate,
        String text,
        String idempotencyKey) {

    public IngestKnowledgeBaseSourceCommand {
        Objects.requireNonNull(sourceType, "sourceType não pode ser nulo");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title é obrigatório");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("o texto da fonte normativa é obrigatório");
        }
        idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.strip();
    }

    /** Pedido sem chave de idempotência. */
    public IngestKnowledgeBaseSourceCommand(
            String title, KnowledgeBaseSourceType sourceType, LocalDate effectiveDate, String text) {
        this(title, sourceType, effectiveDate, text, null);
    }

    public boolean hasIdempotencyKey() {
        return idempotencyKey != null;
    }

    /** O texto da norma não vai para o log; só o seu tamanho. */
    @Override
    public String toString() {
        return "IngestKnowledgeBaseSourceCommand[title=%s, sourceType=%s, effectiveDate=%s, characters=%d]"
                .formatted(title, sourceType, effectiveDate, text.length());
    }
}
