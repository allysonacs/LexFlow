package com.lexflow.application.llm;

import java.util.Objects;

/**
 * Pedido ao LLM.
 *
 * <p>O {@code toString} não inclui os prompts: eles podem carregar dados sensíveis de documentos
 * (seção 12) e não podem ir para o log por descuido.
 *
 * @param systemPrompt instruções de comportamento; opcional
 * @param prompt conteúdo da mensagem do usuário; obrigatório
 * @param outputSchema JSON Schema (em texto) que a resposta deve seguir; opcional. Quando informado,
 *     o provedor é instruído a responder só com JSON nesse formato, e a resposta é validada contra
 *     ele antes de ser devolvida. Todo objeto precisa de {@code "additionalProperties": false}
 * @param model identificador do modelo; nulo usa o modelo padrão configurado
 * @param maxTokens limite de tokens da resposta; nulo usa o padrão configurado
 * @param temperature opcional. <strong>Os modelos atuais (Claude Opus 5 e posteriores) recusam
 *     este parâmetro</strong>; ele existe para modelos antigos. Prefira {@code effort}
 * @param effort esforço de raciocínio; nulo usa o padrão do modelo
 */
public record LlmRequest(
        String systemPrompt,
        String prompt,
        String outputSchema,
        String model,
        Integer maxTokens,
        Double temperature,
        LlmEffort effort) {

    public LlmRequest {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt é obrigatório");
        }
        systemPrompt = blankToNull(systemPrompt);
        outputSchema = blankToNull(outputSchema);
        model = blankToNull(model);
        if (maxTokens != null && maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens deve ser positivo");
        }
        if (temperature != null && (temperature < 0.0 || temperature > 1.0)) {
            throw new IllegalArgumentException("temperature deve estar entre 0.0 e 1.0");
        }
    }

    /** Pedido de texto livre, com os padrões configurados. */
    public static LlmRequest text(String systemPrompt, String prompt) {
        return new LlmRequest(systemPrompt, prompt, null, null, null, null, null);
    }

    /** Pedido de resposta estruturada, com os padrões configurados. */
    public static LlmRequest structured(String systemPrompt, String prompt, String outputSchema) {
        Objects.requireNonNull(outputSchema, "outputSchema não pode ser nulo");
        return new LlmRequest(systemPrompt, prompt, outputSchema, null, null, null, null);
    }

    /** Mesmo pedido, com outro modelo. */
    public LlmRequest withModel(String newModel) {
        return new LlmRequest(systemPrompt, prompt, outputSchema, newModel, maxTokens, temperature, effort);
    }

    /** Mesmo pedido, com outro limite de tokens. */
    public LlmRequest withMaxTokens(int newMaxTokens) {
        return new LlmRequest(systemPrompt, prompt, outputSchema, model, newMaxTokens, temperature, effort);
    }

    /** Mesmo pedido, com outro esforço. */
    public LlmRequest withEffort(LlmEffort newEffort) {
        return new LlmRequest(systemPrompt, prompt, outputSchema, model, maxTokens, temperature, newEffort);
    }

    public boolean expectsStructuredOutput() {
        return outputSchema != null;
    }

    @Override
    public String toString() {
        return "LlmRequest[model=%s, maxTokens=%s, effort=%s, structured=%s, promptLength=%d]"
                .formatted(model, maxTokens, effort, expectsStructuredOutput(), prompt.length());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
