package com.lexflow.domain.exception;

import java.util.UUID;

/** Fonte normativa inexistente. */
public class KnowledgeBaseSourceNotFoundException extends DomainException {

    public KnowledgeBaseSourceNotFoundException(UUID id) {
        super("Fonte normativa não encontrada: " + id);
    }
}
