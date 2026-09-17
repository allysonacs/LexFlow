package com.lexflow.application.pagination;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Uma página de resultados.
 *
 * @param totalElements total de itens em todas as páginas
 */
public record PageResult<T>(List<T> content, int page, int size, long totalElements) {

    public PageResult {
        content = List.copyOf(Objects.requireNonNull(content, "content não pode ser nulo"));
        if (totalElements < 0) {
            throw new IllegalArgumentException("totalElements não pode ser negativo");
        }
    }

    /** Quantidade total de páginas; zero quando não há itens. */
    public int totalPages() {
        return size == 0 ? 0 : (int) ((totalElements + size - 1) / size);
    }

    /** Converte o conteúdo mantendo os dados da paginação. */
    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(content.stream().map(mapper).toList(), page, size, totalElements);
    }
}
