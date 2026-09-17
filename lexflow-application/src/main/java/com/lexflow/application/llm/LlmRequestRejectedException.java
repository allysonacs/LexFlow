package com.lexflow.application.llm;

/**
 * O provedor recusou a requisição: credencial inválida, modelo inexistente, parâmetro não aceito ou
 * conteúdo grande demais.
 *
 * <p>É um defeito de configuração ou de montagem do pedido. Tentar de novo dá o mesmo resultado, por
 * isso não há retry, e a falha não conta para abrir o circuito.
 */
public class LlmRequestRejectedException extends LlmException {

    private final int statusCode;
    private final String errorType;

    public LlmRequestRejectedException(int statusCode, String errorType, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorType = errorType;
    }

    /** Status HTTP da resposta; zero quando a requisição nem chegou a ser enviada. */
    public int statusCode() {
        return statusCode;
    }

    /** Tipo de erro informado pelo provedor (ex.: {@code invalid_request_error}). */
    public String errorType() {
        return errorType;
    }
}
