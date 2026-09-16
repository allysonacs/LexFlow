package com.lexflow.api.legalcase;

import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Resposta do {@code POST /api/v1/legal-cases}.
 *
 * @param statusUrl endereço do {@code GET} de consulta, devolvido no corpo além do cabeçalho
 *     {@code Location} para que o cliente não precise montar a URL por conta própria
 * @param documentCount quantidade de arquivos efetivamente registrados; pode ser menor do que a
 *     quantidade enviada, quando o mesmo arquivo veio repetido na requisição
 */
public record CreateLegalCaseResponse(
        UUID id, LegalCaseStatus status, String statusUrl, int documentCount, Instant createdAt) {

    public static CreateLegalCaseResponse from(LegalCase legalCase, int documentCount, String statusUrl) {
        return new CreateLegalCaseResponse(
                legalCase.id(), legalCase.status(), statusUrl, documentCount, legalCase.createdAt());
    }
}
