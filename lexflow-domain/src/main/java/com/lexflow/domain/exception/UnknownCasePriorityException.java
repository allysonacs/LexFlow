package com.lexflow.domain.exception;

import java.util.Set;

/** Lançada quando a prioridade informada não corresponde a nenhum valor de {@code CasePriority}. */
public class UnknownCasePriorityException extends DomainException {

    private final String informedValue;

    public UnknownCasePriorityException(String informedValue, Set<String> supportedValues) {
        super("Prioridade inválida: '%s'. Valores aceitos: %s"
                .formatted(informedValue, String.join(", ", supportedValues)));
        this.informedValue = informedValue;
    }

    public String informedValue() {
        return informedValue;
    }
}
