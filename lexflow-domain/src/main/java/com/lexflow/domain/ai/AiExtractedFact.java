package com.lexflow.domain.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Fatos extraídos de um documento pela IA, em JSON, sem nenhuma opinião jurídica (seção 10, item 1).
 *
 * <p>O JSON fica como texto: o domínio não conhece biblioteca de serialização, e a validação contra o
 * JSON Schema é responsabilidade da camada de aplicação (Prompt 11).
 *
 * @param modelVersion modelo que efetivamente gerou a extração
 * @param promptVersionId versão do prompt usada (seção 10, item 5)
 */
public record AiExtractedFact(
        UUID id,
        UUID legalCaseId,
        UUID documentId,
        String extractedJson,
        String modelVersion,
        UUID promptVersionId,
        Instant extractedAt) {

    public AiExtractedFact {
        Objects.requireNonNull(id, "id não pode ser nulo");
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        Objects.requireNonNull(documentId, "documentId não pode ser nulo");
        Objects.requireNonNull(extractedAt, "extractedAt não pode ser nulo");
        Objects.requireNonNull(promptVersionId, "promptVersionId não pode ser nulo: toda chamada ao LLM registra o prompt usado");
        if (extractedJson == null || extractedJson.isBlank()) {
            throw new IllegalArgumentException("extractedJson é obrigatório");
        }
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion é obrigatório: rastreabilidade do modelo é exigida");
        }
    }
}
