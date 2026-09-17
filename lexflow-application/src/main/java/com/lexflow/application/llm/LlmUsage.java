package com.lexflow.application.llm;

/** Consumo de tokens de uma chamada, como o provedor informou. */
public record LlmUsage(long inputTokens, long outputTokens, long cacheReadInputTokens, long cacheCreationInputTokens) {

    public static final LlmUsage NONE = new LlmUsage(0, 0, 0, 0);

    public LlmUsage {
        if (inputTokens < 0 || outputTokens < 0 || cacheReadInputTokens < 0 || cacheCreationInputTokens < 0) {
            throw new IllegalArgumentException("contagem de tokens não pode ser negativa");
        }
    }
}
