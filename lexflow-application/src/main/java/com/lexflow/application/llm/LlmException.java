package com.lexflow.application.llm;

import com.lexflow.application.exception.ApplicationException;

/** Base das falhas de chamada ao LLM. */
public abstract class LlmException extends ApplicationException {

    protected LlmException(String message) {
        super(message);
    }

    protected LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
