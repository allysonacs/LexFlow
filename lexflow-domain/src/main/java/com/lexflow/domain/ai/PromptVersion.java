package com.lexflow.domain.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Versão registrada de um template de prompt, correspondente a uma linha de {@code prompt_versions}.
 *
 * <p>Toda chamada ao LLM grava qual versão foi usada (seção 10, item 5). Mudar o texto de um prompt é
 * criar uma nova versão, nunca editar a existente — só assim uma resposta antiga continua explicável
 * pelo prompt que realmente a produziu.
 *
 * @param promptKey identificador do prompt (ex.: {@code FACT_EXTRACTION})
 * @param templateText texto do template, com os marcadores que o código preenche
 * @param active {@code true} para a versão em uso; no máximo uma por chave
 */
public record PromptVersion(
        UUID id, String promptKey, int version, String templateText, boolean active, Instant createdAt) {

    public PromptVersion {
        Objects.requireNonNull(id, "id não pode ser nulo");
        Objects.requireNonNull(createdAt, "createdAt não pode ser nulo");
        if (promptKey == null || promptKey.isBlank()) {
            throw new IllegalArgumentException("promptKey é obrigatório");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version deve ser positiva");
        }
        if (templateText == null || templateText.isBlank()) {
            throw new IllegalArgumentException("templateText é obrigatório");
        }
    }

    /** Rótulo legível, para mensagens e logs: {@code FACT_EXTRACTION v1}. */
    public String label() {
        return "%s v%d".formatted(promptKey, version);
    }
}
