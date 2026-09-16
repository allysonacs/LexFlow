package com.lexflow.domain.exception;

import java.util.Set;

/**
 * Lançada quando um arquivo enviado não está em um dos formatos aceitos pela área jurídica
 * ({@link com.lexflow.domain.document.DocumentFormat}).
 */
public class UnsupportedDocumentFormatException extends DomainException {

    private final String fileName;

    public UnsupportedDocumentFormatException(String fileName, Set<String> supportedExtensions) {
        super("Formato de arquivo não aceito: '%s'. Formatos aceitos: %s"
                .formatted(fileName, String.join(", ", supportedExtensions)));
        this.fileName = fileName;
    }

    public String fileName() {
        return fileName;
    }
}
