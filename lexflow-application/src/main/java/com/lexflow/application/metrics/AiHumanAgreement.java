package com.lexflow.application.metrics;

import com.lexflow.domain.decision.DecisionType;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.time.Instant;
import java.util.UUID;

/**
 * Uma demanda decidida, com a sugestão implícita da IA ao lado do desfecho humano.
 *
 * @param agreed nulo quando a sugestão é inconclusiva: aí não há concordância nem discordância
 * @param minConfidence menor confiança entre as respostas; é ela, e não a média, que decide a
 *     sugestão — uma resposta fraca no meio de respostas fortes ainda é uma resposta fraca
 */
public record AiHumanAgreement(
        UUID legalCaseId,
        LegalCaseType caseType,
        DecisionType decisionType,
        AiSuggestion aiSuggestion,
        Boolean agreed,
        int answerCount,
        Double minConfidence,
        Double averageConfidence,
        int failedVerifications,
        int openAlerts,
        Instant decidedAt) {}
