package com.lexflow.domain.exception;

/**
 * Exceção base de todas as violações de regra de negócio do LexFlow.
 *
 * <p>Toda exceção de domínio deve estender esta classe e ter um nome descritivo, para que as camadas
 * externas consigam distinguir erro de negócio de erro técnico (seção 13 da base de conhecimento).
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
