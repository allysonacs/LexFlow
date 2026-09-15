package com.lexflow.domain.ai;

import com.lexflow.domain.exception.InvalidConfidenceScoreException;

/**
 * Nível de confiança de uma resposta da IA, sempre entre 0.0 e 1.0 (seção 10).
 */
public record ConfidenceScore(double value) {

    public ConfidenceScore {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new InvalidConfidenceScoreException(value);
        }
    }

    public static ConfidenceScore of(double value) {
        return new ConfidenceScore(value);
    }

    /** Confiança nula, usada quando a segunda checagem reprova a resposta (Prompt 14). */
    public static ConfidenceScore zero() {
        return new ConfidenceScore(0.0);
    }

    /** Indica se a confiança está abaixo do limite informado, para sinalizar ao revisor humano. */
    public boolean isBelow(double threshold) {
        return value < threshold;
    }
}
