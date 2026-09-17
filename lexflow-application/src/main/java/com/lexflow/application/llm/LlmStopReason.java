package com.lexflow.application.llm;

/** Por que o modelo parou de gerar. */
public enum LlmStopReason {

    /** Terminou naturalmente. */
    END_TURN,

    /** Atingiu o limite de tokens: a resposta está incompleta. */
    MAX_TOKENS,

    /** Encontrou uma sequência de parada. */
    STOP_SEQUENCE,

    /** Pediu para usar uma ferramenta. */
    TOOL_USE,

    /** Pausou um turno longo, que precisa ser continuado. */
    PAUSE_TURN,

    /** Recusou-se a responder. */
    REFUSAL,

    /** Valor que este cliente ainda não conhece. */
    OTHER;

    /** Converte o valor da API ({@code end_turn}...). */
    public static LlmStopReason fromApiValue(String value) {
        if (value == null) {
            return OTHER;
        }
        return switch (value) {
            case "end_turn" -> END_TURN;
            case "max_tokens" -> MAX_TOKENS;
            case "stop_sequence" -> STOP_SEQUENCE;
            case "tool_use" -> TOOL_USE;
            case "pause_turn" -> PAUSE_TURN;
            case "refusal" -> REFUSAL;
            default -> OTHER;
        };
    }
}
