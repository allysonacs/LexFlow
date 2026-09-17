package com.lexflow.domain.knowledge;

import java.util.Arrays;
import java.util.Objects;

/**
 * Representação vetorial de um texto, usada na recuperação por similaridade (seção 10, item 2).
 *
 * <p>É um value object: o vetor é copiado na entrada e na saída, então nenhum chamador consegue
 * alterar um embedding já gravado. A dimensão precisa bater com a da coluna {@code vector} declarada
 * na migration — trocar de modelo de embeddings exige nova migration e reindexação da base.
 *
 * <p>A similaridade de cosseno vive aqui, e não em um serviço, porque é aritmética pura: o banco a
 * calcula em produção, e o domínio a calcula nos testes, com o mesmo resultado.
 */
public final class Embedding {

    private final float[] values;

    private Embedding(float[] values) {
        this.values = values;
    }

    /**
     * Cria um embedding a partir do vetor devolvido pelo provedor.
     *
     * @throws IllegalArgumentException se o vetor for vazio ou tiver algum valor não finito — um
     *     {@code NaN} gravado na coluna vetorial contamina toda busca posterior
     */
    public static Embedding of(float[] values) {
        Objects.requireNonNull(values, "values não pode ser nulo");
        if (values.length == 0) {
            throw new IllegalArgumentException("embedding não pode ser vazio");
        }
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("embedding não pode conter valores não finitos (NaN ou infinito)");
            }
        }
        return new Embedding(values.clone());
    }

    /** Cria um embedding a partir de uma lista de números, no formato em que a API os devolve. */
    public static Embedding of(java.util.List<? extends Number> values) {
        Objects.requireNonNull(values, "values não pode ser nulo");
        float[] vector = new float[values.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = values.get(i).floatValue();
        }
        return of(vector);
    }

    /** Quantidade de dimensões do vetor. */
    public int dimension() {
        return values.length;
    }

    /** Cópia do vetor, no formato aceito pelo driver do PostgreSQL. */
    public float[] toArray() {
        return values.clone();
    }

    /**
     * Similaridade de cosseno com outro embedding, entre -1.0 e 1.0.
     *
     * @throws IllegalArgumentException se as dimensões não coincidirem
     */
    public double cosineSimilarity(Embedding other) {
        Objects.requireNonNull(other, "other não pode ser nulo");
        if (other.dimension() != dimension()) {
            throw new IllegalArgumentException(
                    "embeddings de dimensões diferentes não são comparáveis: %d e %d"
                            .formatted(dimension(), other.dimension()));
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < values.length; i++) {
            dotProduct += (double) values[i] * other.values[i];
            normA += (double) values[i] * values[i];
            normB += (double) other.values[i] * other.values[i];
        }
        // Um vetor nulo não aponta para lugar nenhum: a similaridade com ele é zero, não uma divisão por zero.
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** Confere a dimensão esperada pelo schema do banco. */
    public Embedding requireDimension(int expected) {
        if (dimension() != expected) {
            throw new IllegalArgumentException(
                    "embedding de dimensão %d, mas a base normativa espera %d".formatted(dimension(), expected));
        }
        return this;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Embedding embedding && Arrays.equals(values, embedding.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    /** Não imprime o vetor: são centenas de números que só poluiriam o log. */
    @Override
    public String toString() {
        return "Embedding[dimension=%d]".formatted(dimension());
    }
}
