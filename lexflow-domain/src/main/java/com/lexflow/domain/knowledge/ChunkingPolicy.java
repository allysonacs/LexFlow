package com.lexflow.domain.knowledge;

/**
 * Parâmetros da divisão de uma fonte normativa em trechos.
 *
 * <p>O tamanho é um equilíbrio: trechos curtos demais perdem o contexto da norma e chegam ao modelo
 * como frases soltas; trechos longos demais diluem o assunto e fazem a busca por similaridade errar o
 * alvo. A sobreposição existe para que uma regra que atravessa a fronteira de dois trechos continue
 * legível em pelo menos um deles.
 *
 * @param maxCharacters tamanho máximo de um trecho
 * @param overlapCharacters quanto do fim de um trecho é repetido no início do seguinte
 */
public record ChunkingPolicy(int maxCharacters, int overlapCharacters) {

    /** Menor tamanho admitido: abaixo disso o trecho deixa de carregar contexto suficiente. */
    public static final int MIN_MAX_CHARACTERS = 200;

    /** Padrão usado quando a configuração não informa outro. */
    public static final ChunkingPolicy DEFAULT = new ChunkingPolicy(1_200, 200);

    public ChunkingPolicy {
        if (maxCharacters < MIN_MAX_CHARACTERS) {
            throw new IllegalArgumentException(
                    "maxCharacters deve ser de ao menos %d".formatted(MIN_MAX_CHARACTERS));
        }
        if (overlapCharacters < 0) {
            throw new IllegalArgumentException("overlapCharacters não pode ser negativo");
        }
        if (overlapCharacters >= maxCharacters) {
            throw new IllegalArgumentException(
                    "overlapCharacters deve ser menor que maxCharacters, ou a divisão nunca avançaria");
        }
    }
}
