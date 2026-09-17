package com.lexflow.application.exception;

/**
 * Lançada quando a extração de texto falha por um problema de ambiente — o OCR não está instalado,
 * o processo externo esgotou o tempo ou não pôde ser iniciado.
 *
 * <p>Ao contrário de {@link UnreadableDocumentException}, o arquivo pode estar perfeito: a falha deve
 * subir, para que a mensagem volte à fila e seja tentada de novo.
 */
public class DocumentTextExtractionException extends ApplicationException {

    public DocumentTextExtractionException(String message) {
        super(message);
    }

    public DocumentTextExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
