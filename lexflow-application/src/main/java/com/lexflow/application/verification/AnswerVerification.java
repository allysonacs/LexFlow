package com.lexflow.application.verification;

import java.util.Objects;

/**
 * Veredito da segunda checagem, já lido do JSON validado.
 *
 * @param supported {@code true} quando os trechos citados sustentam a resposta
 * @param justification explicação curta do veredito, que acompanha a resposta até o revisor humano
 */
public record AnswerVerification(boolean supported, String justification) {

    public AnswerVerification {
        Objects.requireNonNull(justification, "justification não pode ser nula");
    }
}
