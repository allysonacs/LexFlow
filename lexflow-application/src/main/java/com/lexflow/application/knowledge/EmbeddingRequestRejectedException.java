package com.lexflow.application.knowledge;

/**
 * O provedor de embeddings recusou a requisição (credencial, modelo ou parâmetro inválido).
 *
 * <p>Tentar de novo não resolve: é erro de configuração ou de quem montou o pedido.
 */
public class EmbeddingRequestRejectedException extends EmbeddingException {

    private final int status;

    public EmbeddingRequestRejectedException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** Status HTTP devolvido pelo provedor; zero quando a falha foi detectada antes da chamada. */
    public int status() {
        return status;
    }
}
