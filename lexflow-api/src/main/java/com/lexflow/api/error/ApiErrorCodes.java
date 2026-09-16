package com.lexflow.api.error;

/**
 * Códigos de erro do contrato da API.
 *
 * <p>São constantes porque fazem parte do contrato com o cliente: mudar um valor daqui quebra quem
 * já trata o erro do outro lado.
 */
public final class ApiErrorCodes {

    /** Requisição malformada: campo obrigatório ausente, valor inválido ou arquivo recusado. */
    public static final String INVALID_REQUEST = "INVALID_REQUEST";

    /** Recurso inexistente. */
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";

    /** Conflito com o estado atual do recurso, incluindo transição de status não permitida. */
    public static final String CONFLICT = "CONFLICT";

    /** Storage de documentos indisponível ou recusando a operação. */
    public static final String STORAGE_UNAVAILABLE = "STORAGE_UNAVAILABLE";

    /** Arquivo maior do que o limite configurado. */
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";

    /** Credenciais ausentes ou inválidas. */
    public static final String UNAUTHORIZED = "UNAUTHORIZED";

    /** Credenciais válidas, mas sem o papel exigido pela rota. */
    public static final String FORBIDDEN = "FORBIDDEN";

    /** Falha não prevista: nada do detalhe interno é devolvido ao cliente. */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ApiErrorCodes() {
        // classe de constantes
    }
}
