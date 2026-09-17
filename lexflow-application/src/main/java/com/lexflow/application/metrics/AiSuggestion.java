package com.lexflow.application.metrics;

/**
 * Sugestão implícita da IA sobre uma demanda (Prompt 18, item 3).
 *
 * <p><strong>A IA não emite esta sugestão.</strong> Ela é uma leitura do conjunto das respostas, feita
 * pelo sistema: nenhuma resposta individual diz "aprove" ou "reprove", e o princípio da seção 1
 * continua valendo — a IA instrui, o humano decide. A sugestão existe apenas para que se possa medir
 * o quanto a análise da IA e a decisão humana caminham juntas.
 */
public enum AiSuggestion {

    /** Todas as perguntas respondidas, com fundamento, confiança alta e nenhum alerta em aberto. */
    FAVORABLE,

    /** Algo pede atenção: verificação reprovada, ausência de fundamento, confiança baixa ou alerta. */
    UNFAVORABLE,

    /** Sem respostas suficientes para uma leitura; não conta como acerto nem como erro. */
    INCONCLUSIVE
}
