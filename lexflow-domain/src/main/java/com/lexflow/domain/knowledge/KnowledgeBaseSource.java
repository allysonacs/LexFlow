package com.lexflow.domain.knowledge;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Fonte normativa indexada para RAG: uma lei, uma norma ou uma política interna (seção 6).
 *
 * <p>A fonte guarda apenas a identificação; o texto fica nos {@link KnowledgeBaseChunk} dela, que são
 * o que a recuperação devolve e o que o revisor humano lê ao conferir uma citação.
 *
 * @param title identificação legível da fonte, como ela é citada (ex.: "Lei 14.133/2021")
 * @param effectiveDate data de vigência; opcional, porque nem toda política interna a declara
 */
public record KnowledgeBaseSource(UUID id, String title, KnowledgeBaseSourceType sourceType, LocalDate effectiveDate) {

    /** Limite da coluna {@code knowledge_base_sources.title}. */
    public static final int MAX_TITLE_LENGTH = 500;

    public KnowledgeBaseSource {
        Objects.requireNonNull(id, "id não pode ser nulo");
        Objects.requireNonNull(sourceType, "sourceType não pode ser nulo");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title é obrigatório: um trecho citado sem fonte identificada não serve ao revisor humano");
        }
        title = title.strip();
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("title deve ter no máximo %d caracteres".formatted(MAX_TITLE_LENGTH));
        }
    }
}
