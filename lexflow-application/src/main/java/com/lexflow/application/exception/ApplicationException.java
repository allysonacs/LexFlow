package com.lexflow.application.exception;

/**
 * Exceção base de falhas de orquestração da camada de aplicação.
 *
 * <p>Distinta de {@link com.lexflow.domain.exception.DomainException}: aqui não se viola uma regra
 * jurídica, e sim uma regra de condução do caso de uso (idempotência, concorrência, ordem de
 * chamadas).
 */
public abstract class ApplicationException extends RuntimeException {

    protected ApplicationException(String message) {
        super(message);
    }
}
