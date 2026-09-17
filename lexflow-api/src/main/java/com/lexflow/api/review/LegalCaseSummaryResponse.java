package com.lexflow.api.review;

import com.lexflow.domain.legalcase.LegalCase;
import java.time.Instant;
import java.util.UUID;

/**
 * Demanda em uma listagem, com o mínimo para a fila de revisão humana ser exibida e ordenada.
 *
 * <p>A listagem não traz as respostas da IA: elas são muitas e longas, e quem abre a fila quer
 * escolher um caso, não ler todos. O detalhe vem do {@code GET /api/v1/legal-cases/{id}/analysis}.
 */
public record LegalCaseSummaryResponse(
        UUID id,
        String externalReference,
        String caseType,
        String status,
        String requester,
        String priority,
        Instant createdAt,
        Instant updatedAt) {

    public static LegalCaseSummaryResponse from(LegalCase legalCase) {
        return new LegalCaseSummaryResponse(
                legalCase.id(),
                legalCase.externalReference(),
                legalCase.caseType().name(),
                legalCase.status().name(),
                legalCase.requester(),
                legalCase.priority().name(),
                legalCase.createdAt(),
                legalCase.updatedAt());
    }
}
