package com.lexflow.api.error;

import java.time.Instant;
import java.util.List;

/**
 * Formato único de erro da API.
 *
 * <p>Ter um só formato importa mais do que o formato em si: o cliente escreve um tratamento de erro
 * uma vez e ele vale para qualquer endpoint.
 *
 * @param code código estável, em {@code UPPER_SNAKE_CASE}; é ele que o cliente deve testar, não a
 *     mensagem, que pode ser reescrita a qualquer momento
 * @param message descrição legível do problema, em português
 * @param timestamp momento em que o erro foi produzido, em UTC
 * @param path recurso que foi chamado
 * @param details informações adicionais, como a lista de campos inválidos; omitido quando vazio
 */
public record ApiErrorResponse(String code, String message, Instant timestamp, String path, List<String> details) {

    public static ApiErrorResponse of(String code, String message, Instant timestamp, String path) {
        return new ApiErrorResponse(code, message, timestamp, path, null);
    }
}
