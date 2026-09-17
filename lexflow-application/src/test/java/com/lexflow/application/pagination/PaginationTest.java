package com.lexflow.application.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaginationTest {

    @Test
    @DisplayName("a página pedida respeita os limites")
    void shouldValidatePageQuery() {
        assertThat(PageQuery.of(0, PageQuery.MAX_SIZE).size()).isEqualTo(100);
        assertThatIllegalArgumentException().isThrownBy(() -> PageQuery.of(-1, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> PageQuery.of(0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> PageQuery.of(0, PageQuery.MAX_SIZE + 1));
    }

    @Test
    @DisplayName("o resultado calcula o total de páginas e converte o conteúdo")
    void shouldComputeTotalPagesAndMap() {
        PageResult<Integer> result = new PageResult<>(List.of(1, 2), 0, 2, 5);

        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(new PageResult<>(List.of(), 0, 2, 0).totalPages()).isZero();
        assertThat(new PageResult<>(List.of(), 0, 0, 0).totalPages()).isZero();
        assertThat(result.map(value -> "n" + value)).satisfies(mapped -> {
            assertThat(mapped.content()).containsExactly("n1", "n2");
            assertThat(mapped.totalElements()).isEqualTo(5);
        });
        assertThatIllegalArgumentException().isThrownBy(() -> new PageResult<>(List.of(), 0, 1, -1));
    }
}
