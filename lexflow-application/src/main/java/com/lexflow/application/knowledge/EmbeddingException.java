package com.lexflow.application.knowledge;

import com.lexflow.application.exception.ApplicationException;

/** Falha ao gerar embeddings para a base normativa. */
public abstract class EmbeddingException extends ApplicationException {

    protected EmbeddingException(String message) {
        super(message);
    }

    protected EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
