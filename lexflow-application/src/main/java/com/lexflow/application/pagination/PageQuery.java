package com.lexflow.application.pagination;

/**
 * Página pedida por uma consulta de listagem.
 *
 * @param page índice da página, começando em zero
 * @param size quantidade de itens por página, de 1 a {@link #MAX_SIZE}
 */
public record PageQuery(int page, int size) {

    /** Limite de itens por página: impede que uma listagem vire, na prática, uma consulta sem limite. */
    public static final int MAX_SIZE = 100;

    public static final int DEFAULT_SIZE = 20;

    public PageQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page não pode ser negativo");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size deve estar entre 1 e %d".formatted(MAX_SIZE));
        }
    }

    public static PageQuery of(int page, int size) {
        return new PageQuery(page, size);
    }
}
