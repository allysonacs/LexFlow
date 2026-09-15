package com.lexflow.domain.exception;

/**
 * Lançada quando um nível de confiança fora do intervalo 0.0–1.0 é informado.
 */
public class InvalidConfidenceScoreException extends DomainException {

    public InvalidConfidenceScoreException(double value) {
        super("Nível de confiança deve estar entre 0.0 e 1.0, mas foi informado: %s".formatted(value));
    }
}
