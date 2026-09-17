package com.lexflow.domain.exception;

import java.util.Set;

/** Tipo de fonte normativa não reconhecido pelo glossário. */
public class UnknownKnowledgeBaseSourceTypeException extends DomainException {

    public UnknownKnowledgeBaseSourceTypeException(String value, Set<String> supportedValues) {
        super("Tipo de fonte normativa desconhecido: '%s'. Valores aceitos: %s"
                .formatted(value, String.join(", ", supportedValues)));
    }
}
