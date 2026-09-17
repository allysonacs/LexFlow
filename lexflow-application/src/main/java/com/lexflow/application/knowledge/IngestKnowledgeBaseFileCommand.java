package com.lexflow.application.knowledge;

import com.lexflow.domain.knowledge.KnowledgeBaseSourceType;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Pedido de indexação de uma fonte normativa enviada como arquivo.
 *
 * <p>O texto é extraído do arquivo pelo mesmo {@link com.lexflow.application.document.DocumentTextExtractor}
 * usado nos documentos das demandas, inclusive com OCR quando a norma chega digitalizada.
 *
 * @param declaredMimeType tipo declarado pelo cliente; a extensão é que decide o formato
 * @param idempotencyKey opcional; com ela, reenviar a mesma norma não a indexa duas vezes
 */
public record IngestKnowledgeBaseFileCommand(
        String title,
        KnowledgeBaseSourceType sourceType,
        LocalDate effectiveDate,
        String fileName,
        String declaredMimeType,
        byte[] content,
        String idempotencyKey) {

    public IngestKnowledgeBaseFileCommand {
        Objects.requireNonNull(sourceType, "sourceType não pode ser nulo");
        Objects.requireNonNull(content, "content não pode ser nulo");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title é obrigatório");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName é obrigatório");
        }
        if (content.length == 0) {
            throw new IllegalArgumentException("arquivo '%s' está vazio".formatted(fileName));
        }
        idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.strip();
    }

    /** Pedido sem chave de idempotência. */
    public IngestKnowledgeBaseFileCommand(
            String title,
            KnowledgeBaseSourceType sourceType,
            LocalDate effectiveDate,
            String fileName,
            String declaredMimeType,
            byte[] content) {
        this(title, sourceType, effectiveDate, fileName, declaredMimeType, content, null);
    }

    @Override
    public String toString() {
        return "IngestKnowledgeBaseFileCommand[title=%s, sourceType=%s, fileName=%s, bytes=%d]"
                .formatted(title, sourceType, fileName, content.length);
    }
}
