package com.lexflow.domain.exception;

/**
 * Lançada quando um código de tipo de documento não segue o formato {@code UPPER_SNAKE_CASE}.
 */
public class InvalidDocumentTypeException extends DomainException {

    public InvalidDocumentTypeException(String informedValue, int maxLength) {
        super("Tipo de documento inválido: '%s'. Use letras, dígitos e '_', começando por letra, com até %d caracteres (ex.: CONTRACT_DRAFT)"
                .formatted(informedValue, maxLength));
    }
}
