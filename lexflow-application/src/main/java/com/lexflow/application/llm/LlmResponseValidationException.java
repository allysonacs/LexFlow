package com.lexflow.application.llm;

import java.util.List;

/**
 * A resposta do LLM não pode ser usada: não é JSON, não segue o schema pedido ou foi cortada pelo
 * limite de tokens.
 *
 * <p>Nada de uma resposta assim segue adiante no pipeline. Quem chama decide se tenta de novo com
 * um prompt reforçado (Prompt 11); o cliente não repete a chamada sozinho, porque o provedor
 * respondeu normalmente e repetir o mesmo pedido tende a dar o mesmo resultado.
 */
public class LlmResponseValidationException extends LlmException {

    private final String model;
    private final List<String> violations;

    public LlmResponseValidationException(String message, String model, List<String> violations) {
        super(message);
        this.model = model;
        this.violations = List.copyOf(violations);
    }

    /** Modelo que produziu a resposta inválida. */
    public String model() {
        return model;
    }

    /** Violações encontradas, sem o conteúdo da resposta. */
    public List<String> violations() {
        return violations;
    }
}
