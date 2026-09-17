package com.lexflow.application.llm;

import java.time.Duration;
import java.util.Objects;

/**
 * Resposta do LLM, já validada.
 *
 * <p>O {@code toString} não inclui o texto, pelo mesmo motivo de {@link LlmRequest}.
 *
 * @param messageId identificador da mensagem no provedor
 * @param requestedModel modelo pedido
 * @param model modelo que efetivamente respondeu — é o que deve ser gravado como {@code model_version}
 *     (seção 10). Pode diferir do pedido quando o provedor recorre a outro modelo
 * @param text texto da resposta; em respostas estruturadas, o próprio JSON
 * @param structuredOutput JSON validado contra o schema pedido; nulo quando não houve schema
 * @param requestId identificador da requisição no provedor, para suporte e rastreio
 * @param latency tempo total da chamada, incluindo as novas tentativas
 */
public record LlmResponse(
        String messageId,
        String requestedModel,
        String model,
        String text,
        String structuredOutput,
        LlmStopReason stopReason,
        LlmUsage usage,
        String requestId,
        Duration latency) {

    public LlmResponse {
        Objects.requireNonNull(requestedModel, "requestedModel não pode ser nulo");
        Objects.requireNonNull(text, "text não pode ser nulo");
        Objects.requireNonNull(stopReason, "stopReason não pode ser nulo");
        Objects.requireNonNull(usage, "usage não pode ser nulo");
        Objects.requireNonNull(latency, "latency não pode ser nulo");
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model é obrigatório: toda resposta registra o modelo que a gerou");
        }
    }

    /** Indica se a resposta veio de um modelo diferente do pedido. */
    public boolean servedByFallbackModel() {
        return !model.equals(requestedModel);
    }

    /** Indica se a resposta foi cortada pelo limite de tokens. */
    public boolean isTruncated() {
        return stopReason == LlmStopReason.MAX_TOKENS;
    }

    @Override
    public String toString() {
        return "LlmResponse[messageId=%s, model=%s, stopReason=%s, usage=%s, requestId=%s, latency=%s, textLength=%d]"
                .formatted(messageId, model, stopReason, usage, requestId, latency, text.length());
    }
}
