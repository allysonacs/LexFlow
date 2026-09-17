package com.lexflow.domain.ai;

/**
 * Origem de uma resposta a uma pergunta jurídica.
 *
 * <p>Existe porque nem toda resposta vem do modelo. Duas situações são resolvidas por código, e o
 * revisor humano precisa saber disso ao ler a análise: uma resposta determinística não tem confiança
 * estatística nem trecho citado — ela é consequência de uma regra que ele pode conferir sozinho.
 */
public enum AnswerSource {

    /** Resposta gerada pelo LLM, fundamentada nos trechos normativos recuperados. */
    LLM,

    /**
     * Resposta produzida por regra de código, sem chamar o modelo.
     *
     * <p>Hoje são duas: documentação incompleta segundo o checklist determinístico (seção 9) e
     * ausência de qualquer trecho normativo suficientemente próximo da pergunta (Prompt 13).
     */
    DETERMINISTIC;

    public boolean isFromLlm() {
        return this == LLM;
    }
}
