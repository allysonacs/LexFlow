package com.lexflow.application.exception;

/**
 * Lançada quando o conteúdo de um documento não pode ser interpretado: arquivo corrompido, protegido
 * por senha ou que não corresponde ao formato declarado.
 *
 * <p>É um problema do arquivo, não do ambiente: repetir a tentativa daria o mesmo resultado. Por isso
 * quem a recebe registra o documento como falho e segue com os demais.
 */
public class UnreadableDocumentException extends ApplicationException {

    public UnreadableDocumentException(String message) {
        super(message);
    }

    public UnreadableDocumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
