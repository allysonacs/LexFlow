package com.lexflow.application.analysis;

import java.util.UUID;

/**
 * Segunda checagem das respostas críticas de uma demanda (Prompt 14).
 *
 * <p>É uma interface, e não uma chamada direta ao caso de uso, por dois motivos: a etapa pode ser
 * desligada por configuração, e a análise (Prompt 13) precisa dispará-la antes de levar a demanda ao
 * revisor humano — uma resposta reprovada tem de chegar já sinalizada, e não sinalizada depois.
 */
public interface AiAnalysisVerifier {

    /**
     * Verifica as respostas críticas ainda não verificadas da demanda.
     *
     * <p>A implementação nunca reescreve uma resposta: ela apenas registra se o texto citado a
     * sustenta.
     */
    VerificationSummary verify(UUID legalCaseId);

    /** Verificador que não faz nada, usado quando a etapa está desligada. */
    static AiAnalysisVerifier disabled() {
        return legalCaseId -> VerificationSummary.NONE;
    }
}
