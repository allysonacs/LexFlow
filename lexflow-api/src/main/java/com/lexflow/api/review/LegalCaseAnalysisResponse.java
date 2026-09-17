package com.lexflow.api.review;

import com.lexflow.api.legalcase.LegalCaseDetailResponse.AlertResponse;
import com.lexflow.application.review.LegalCaseAnalysisView;
import java.util.List;
import java.util.UUID;

/**
 * Tudo o que o revisor humano vê antes de decidir (Prompt 15, item 2).
 *
 * @param awaitsDecision {@code true} enquanto a demanda está em {@code PENDING_HUMAN_REVIEW}
 */
public record LegalCaseAnalysisResponse(
        UUID id,
        String caseType,
        String status,
        boolean awaitsDecision,
        List<AnsweredQuestionResponse> questions,
        List<AlertResponse> openAlerts,
        List<DecisionResponse> decisions) {

    public static LegalCaseAnalysisResponse from(LegalCaseAnalysisView view) {
        return new LegalCaseAnalysisResponse(
                view.legalCase().id(),
                view.legalCase().caseType().name(),
                view.legalCase().status().name(),
                view.awaitsDecision(),
                view.questions().stream().map(AnsweredQuestionResponse::from).toList(),
                view.openAlerts().stream().map(AlertResponse::from).toList(),
                view.decisions().stream().map(DecisionResponse::from).toList());
    }
}
