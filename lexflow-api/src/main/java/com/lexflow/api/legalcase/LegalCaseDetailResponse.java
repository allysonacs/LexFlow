package com.lexflow.api.legalcase;

import com.lexflow.application.legalcase.LegalCaseWithDocuments;
import com.lexflow.domain.alert.LegalCaseAlert;
import com.lexflow.domain.alert.LegalCaseAlertType;
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
        List<DocumentSummaryResponse> documents,
        List<AlertResponse> alerts) {

    /**
     * Alerta da demanda: algo que o pipeline não resolveu sozinho e que a segura até um humano tratar.
     */
    public record AlertResponse(
            UUID id, LegalCaseAlertType type, UUID documentId, String message, Instant createdAt, Instant resolvedAt) {

        public static AlertResponse from(LegalCaseAlert alert) {
            return new AlertResponse(
                    alert.id(), alert.type(), alert.documentId(), alert.message(), alert.createdAt(), alert.resolvedAt());
        }
    }

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
                legalCase.documents().stream().map(DocumentSummaryResponse::from).toList(),
                legalCase.alerts().stream().map(AlertResponse::from).toList());
    }
}
