package com.lexflow.application.llm;

/**
 * O provedor não pôde atender agora: fora do ar, sobrecarregado, lento demais, limite de uso
 * atingido ou circuito aberto.
 *
 * <p>É transitório. Quem consome uma fila deve deixar a exceção subir, para que a mensagem seja
 * tentada de novo mais tarde (seção 11).
 */
public class LlmUnavailableException extends LlmException {

    private final int statusCode;

    public LlmUnavailableException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    /** Status HTTP da resposta, ou zero quando não houve resposta (timeout, conexão, circuito aberto). */
    public int statusCode() {
        return statusCode;
    }
}
