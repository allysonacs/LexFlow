package com.lexflow.application.exception;

/**
 * Lançada quando o storage de documentos falha.
 *
 * <p>Traduz a falha do adapter — S3, MinIO ou o que vier — para um tipo que as camadas de dentro
 * entendem, sem que nenhuma delas precise conhecer as exceções do SDK da AWS.
 */
public class DocumentStorageException extends ApplicationException {

    public DocumentStorageException(String message) {
        super(message);
    }

    public DocumentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
