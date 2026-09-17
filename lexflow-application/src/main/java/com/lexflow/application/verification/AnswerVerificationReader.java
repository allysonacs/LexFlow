package com.lexflow.application.verification;

/**
 * Porta que lê o JSON da segunda checagem, já validado contra {@link AnswerVerificationSchema}.
 *
 * <p>Existe pelo mesmo motivo da leitura das respostas jurídicas: a camada de aplicação não conhece
 * biblioteca de serialização (seção 7).
 */
public interface AnswerVerificationReader {

    /**
     * Lê o JSON do veredito.
     *
     * @throws IllegalArgumentException se o texto não for um JSON no formato do schema
     */
    AnswerVerification read(String json);
}
