package com.lexflow.application.analysis;

import java.util.List;
import java.util.Objects;

/**
 * Conteúdo de uma resposta do LLM, já lido do JSON validado.
 *
 * <p>Os campos vêm como o modelo os escreveu: {@code citedChunks} ainda é texto, e não
 * {@code UUID}, porque conferir se cada identificador é válido e se estava entre os trechos
 * fornecidos é justamente uma das barreiras do caso de uso.
 *
 * @param alerts pontos que o modelo pede que o revisor humano verifique por conta própria
 */
public record LegalAnalysisAnswer(
        String questionKey, String answer, double confidenceScore, List<String> citedChunks, List<String> alerts) {

    public LegalAnalysisAnswer {
        Objects.requireNonNull(questionKey, "questionKey não pode ser nulo");
        Objects.requireNonNull(answer, "answer não pode ser nulo");
        citedChunks = List.copyOf(Objects.requireNonNull(citedChunks, "citedChunks não pode ser nulo"));
        alerts = List.copyOf(Objects.requireNonNull(alerts, "alerts não pode ser nulo"));
    }
}
