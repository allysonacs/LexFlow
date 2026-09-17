package com.lexflow.domain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmbeddingTest {

    @Test
    @DisplayName("o vetor é copiado na entrada e na saída, então ninguém altera um embedding gravado")
    void shouldBeImmutable() {
        float[] original = {1.0f, 2.0f, 3.0f};
        Embedding embedding = Embedding.of(original);

        original[0] = 99.0f;
        embedding.toArray()[1] = 99.0f;

        assertThat(embedding.toArray()).containsExactly(1.0f, 2.0f, 3.0f);
    }

    @Test
    @DisplayName("vetores idênticos têm similaridade 1, e opostos, -1")
    void shouldComputeCosineSimilarity() {
        Embedding vector = Embedding.of(new float[] {1.0f, 0.0f});

        assertThat(vector.cosineSimilarity(Embedding.of(new float[] {1.0f, 0.0f}))).isCloseTo(1.0, within(1e-6));
        assertThat(vector.cosineSimilarity(Embedding.of(new float[] {-1.0f, 0.0f}))).isCloseTo(-1.0, within(1e-6));
        assertThat(vector.cosineSimilarity(Embedding.of(new float[] {0.0f, 1.0f}))).isCloseTo(0.0, within(1e-6));
        // A escala não muda a direção: o cosseno ignora o comprimento do vetor.
        assertThat(vector.cosineSimilarity(Embedding.of(new float[] {5.0f, 0.0f}))).isCloseTo(1.0, within(1e-6));
    }

    @Test
    @DisplayName("um vetor nulo não aponta para lugar nenhum: similaridade zero, sem divisão por zero")
    void shouldReturnZeroForZeroVector() {
        assertThat(Embedding.of(new float[] {0.0f, 0.0f})
                        .cosineSimilarity(Embedding.of(new float[] {1.0f, 1.0f})))
                .isZero();
    }

    @Test
    @DisplayName("um vetor não finito é recusado: um NaN gravado contamina toda busca posterior")
    void shouldRejectNonFiniteValues() {
        assertThatThrownBy(() -> Embedding.of(new float[] {1.0f, Float.NaN}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("não finitos");
        assertThatThrownBy(() -> Embedding.of(new float[] {Float.POSITIVE_INFINITY}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Embedding.of(new float[0])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("dimensões diferentes não são comparáveis nem gravadas")
    void shouldRejectMismatchedDimensions() {
        Embedding embedding = Embedding.of(new float[] {1.0f, 2.0f});

        assertThatThrownBy(() -> embedding.cosineSimilarity(Embedding.of(new float[] {1.0f})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensões diferentes");
        assertThatThrownBy(() -> embedding.requireDimension(1536))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1536");
        assertThat(embedding.requireDimension(2)).isSameAs(embedding);
    }

    @Test
    @DisplayName("é criado a partir da lista de números devolvida pela API")
    void shouldBeCreatedFromNumberList() {
        Embedding embedding = Embedding.of(java.util.List.of(0.5, 0.25, 0.125));

        assertThat(embedding.dimension()).isEqualTo(3);
        assertThat(embedding.toArray()).containsExactly(0.5f, 0.25f, 0.125f);
    }

    @Test
    @DisplayName("igualdade é por valor, e o toString não despeja o vetor no log")
    void shouldCompareByValueAndHideValuesInToString() {
        Embedding first = Embedding.of(new float[] {1.0f, 2.0f});
        Embedding second = Embedding.of(new float[] {1.0f, 2.0f});

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(Embedding.of(new float[] {1.0f, 3.0f}));
        assertThat(first).isNotEqualTo("não é um embedding");
        assertThat(first.toString()).isEqualTo("Embedding[dimension=2]").doesNotContain("1.0");
    }
}
