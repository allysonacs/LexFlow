package com.lexflow.application.knowledge;

/**
 * Provedor de embeddings fora do ar, sobrecarregado, lento demais ou com o circuito aberto.
 *
 * <p>Vale tentar de novo mais tarde: quem indexa uma fonte pode reenviá-la, e quem recupera trechos
 * deixa a exceção subir para a mensagem voltar à fila.
 */
public class EmbeddingUnavailableException extends EmbeddingException {

    public EmbeddingUnavailableException(String message) {
        super(message);
    }

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
