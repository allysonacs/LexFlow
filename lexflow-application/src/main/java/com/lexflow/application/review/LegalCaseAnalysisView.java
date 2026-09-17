package com.lexflow.application.review;

import com.lexflow.domain.alert.LegalCaseAlert;
import com.lexflow.domain.decision.Decision;
import com.lexflow.domain.legalcase.LegalCase;
import java.util.List;
import java.util.Objects;

/**
 * Tudo o que o revisor humano precisa ver para decidir sobre uma demanda (Prompt 15, item 2).
 *
 * @param questions perguntas respondidas, na ordem em que são feitas
 * @param openAlerts alertas em aberto do pipeline: o que a IA não conseguiu resolver sozinha
 * @param decisions decisões já registradas; vazia enquanto a demanda aguarda revisão
 */
public record LegalCaseAnalysisView(
        LegalCase legalCase,
        List<AnsweredQuestion> questions,
        List<LegalCaseAlert> openAlerts,
        List<Decision> decisions) {

    public LegalCaseAnalysisView {
        Objects.requireNonNull(legalCase, "legalCase não pode ser nulo");
        questions = List.copyOf(Objects.requireNonNull(questions, "questions não pode ser nulo"));
        openAlerts = List.copyOf(Objects.requireNonNull(openAlerts, "openAlerts não pode ser nulo"));
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions não pode ser nulo"));
    }

    /** Indica se a demanda está aguardando a decisão de uma pessoa. */
    public boolean awaitsDecision() {
        return legalCase.status() == com.lexflow.domain.legalcase.LegalCaseStatus.PENDING_HUMAN_REVIEW;
    }
}
