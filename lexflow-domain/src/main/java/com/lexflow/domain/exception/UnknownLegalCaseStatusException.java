package com.lexflow.domain.exception;

import java.util.Set;

/** Status de demanda não reconhecido pela máquina de estados. */
public class UnknownLegalCaseStatusException extends DomainException {

    public UnknownLegalCaseStatusException(String value, Set<String> supportedValues) {
        super("Status de demanda desconhecido: '%s'. Valores aceitos: %s"
                .formatted(value, String.join(", ", supportedValues)));
    }
}
