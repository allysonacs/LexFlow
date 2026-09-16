package com.lexflow.api.legalcase;

import com.lexflow.application.legalcase.LegalCaseWithDocuments;
import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Resposta do {@code GET /api/v1/legal-cases/{id}}: situação atual e metadados básicos. */
public record LegalCaseDetailResponse(
        UUID id,
        String externalReference,
        LegalCaseType caseType,
        LegalCaseStatus status,
        String requester,
        String description,
        CasePriority priority,
        Instant createdAt,
        Instant updatedAt,
        List<DocumentSummaryResponse> documents) {

    public static LegalCaseDetailResponse from(LegalCaseWithDocuments legalCase) {
        return new LegalCaseDetailResponse(
                legalCase.legalCase().id(),
                legalCase.legalCase().externalReference(),
                legalCase.legalCase().caseType(),
                legalCase.legalCase().status(),
                legalCase.legalCase().requester(),
                legalCase.legalCase().description(),
                legalCase.legalCase().priority(),
                legalCase.legalCase().createdAt(),
                legalCase.legalCase().updatedAt(),
                legalCase.documents().stream().map(DocumentSummaryResponse::from).toList());
    }
}
