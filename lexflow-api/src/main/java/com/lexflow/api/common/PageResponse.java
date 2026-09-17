package com.lexflow.api.common;

import com.lexflow.application.pagination.PageResult;
import java.util.List;
import java.util.function.Function;

/**
 * Página de resultados no contrato REST — o formato de toda listagem da API (seção 11).
 *
 * @param page índice da página, começando em zero
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <S, T> PageResponse<T> from(PageResult<S> result, Function<S, T> mapper) {
        return new PageResponse<>(
                result.content().stream().map(mapper).toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages());
    }
}
