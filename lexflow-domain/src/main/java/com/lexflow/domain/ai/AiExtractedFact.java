package com.lexflow.domain.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Fatos extraídos de um documento pela IA, em JSON, sem nenhuma opinião jurídica (seção 10, item 1).
 *
 * <p>O JSON fica como texto: o domínio não conhece biblioteca de serialização, e a validação contra o
 * JSON Schema é responsabilidade da camada de aplicação (Prompt 11).
 */
public record AiExtractedFact(
        UUID id, UUID legalCaseId, UUID documentId, String extractedJson, String modelVersion, Instant extractedAt) {

    public AiExtractedFact {
        Objects.requireNonNull(id, "id não pode ser nulo");
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        Objects.requireNonNull(documentId, "documentId não pode ser nulo");
        Objects.requireNonNull(extractedAt, "extractedAt não pode ser nulo");
        if (extractedJson == null || extractedJson.isBlank()) {
            throw new IllegalArgumentException("extractedJson é obrigatório");
        }
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion é obrigatório: rastreabilidade do modelo é exigida");
        }
    }
}
