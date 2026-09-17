package com.lexflow.application.llm;

/**
 * O modelo se recusou a responder ({@code stop_reason: refusal}).
 *
 * <p>Não é falha técnica: a demanda precisa de atenção humana, e repetir o pedido não ajuda.
 */
public class LlmRefusalException extends LlmException {

    private final String model;
    private final String category;

    public LlmRefusalException(String model, String category) {
        super("O modelo %s se recusou a responder (categoria: %s)".formatted(model, category));
        this.model = model;
        this.category = category;
    }

    public String model() {
        return model;
    }

    /** Categoria informada pelo provedor; pode ser nula. */
    public String category() {
        return category;
    }
}
