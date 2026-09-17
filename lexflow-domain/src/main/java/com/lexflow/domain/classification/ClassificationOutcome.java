package com.lexflow.domain.classification;

/**
 * Como o tipo final da demanda foi decidido e quanto as palavras-chave o sustentam.
 *
 * <p>Quando o requisitante informa o tipo, é ele que vale: a classificação por palavras-chave serve
 * de validação cruzada e nunca troca o tipo por conta própria. Uma divergência é sinalizada para o
 * responsável humano, que é quem decide (seção 1 da base de conhecimento).
 */
public enum ClassificationOutcome {

    /** O tipo informado está entre os mais apontados pelas palavras-chave. */
    CONFIRMED,

    /** O tipo foi informado, mas nenhuma palavra-chave foi encontrada para confirmá-lo ou contestá-lo. */
    UNCONFIRMED,

    /** O tipo foi informado, mas as palavras-chave apontam para outro tipo. */
    DIVERGENT,

    /** O tipo não foi informado e foi deduzido das palavras-chave, sem empate. */
    INFERRED;

    /** Indica se o resultado merece a atenção do responsável humano. */
    public boolean requiresAttention() {
        return this == DIVERGENT;
    }
}
