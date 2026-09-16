package com.lexflow.domain.exception;

import java.util.Set;

/**
 * Lançada quando o tipo de demanda informado não existe no glossário da seção 5 da base de
 * conhecimento.
 */
public class UnknownLegalCaseTypeException extends DomainException {

    private final String informedValue;

    public UnknownLegalCaseTypeException(String informedValue, Set<String> supportedValues) {
        super("Tipo de demanda inválido: '%s'. Valores aceitos: %s"
                .formatted(informedValue, String.join(", ", supportedValues)));
        this.informedValue = informedValue;
    }

    public String informedValue() {
        return informedValue;
    }
}
