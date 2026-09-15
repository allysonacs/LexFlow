package com.lexflow.domain.exception;

/**
 * Lançada quando uma resposta da IA chega sem nenhum trecho normativo citado.
 *
 * <p>Regra da seção 10 da base de conhecimento: nenhuma resposta é aceita sem {@code cited_chunks},
 * exceto quando a própria resposta declara que a informação não foi encontrada na base normativa.
 * Esta é a principal barreira do domínio contra alucinação.
 */
public class MissingCitedChunksException extends DomainException {

    public MissingCitedChunksException(String questionKey) {
        super("Resposta da IA para a pergunta '%s' não citou nenhum trecho normativo".formatted(questionKey));
    }
}
