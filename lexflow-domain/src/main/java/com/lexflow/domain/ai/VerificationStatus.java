package com.lexflow.domain.ai;

/**
 * Resultado da segunda checagem de uma resposta da IA (seção 10, item 4).
 *
 * <p>A coluna correspondente em {@code ai_analysis_responses} é criada no Prompt 14; o domínio já
 * nasce com o conceito para não precisar reescrever o agregado depois.
 */
public enum VerificationStatus {

    /** Resposta ainda não submetida à segunda checagem, ou pergunta não crítica. */
    NOT_VERIFIED,

    /** A segunda checagem confirmou que a resposta é sustentada pelos trechos citados. */
    VERIFIED,

    /** A segunda checagem não confirmou a resposta: exige atenção redobrada do revisor humano. */
    FAILED
}
