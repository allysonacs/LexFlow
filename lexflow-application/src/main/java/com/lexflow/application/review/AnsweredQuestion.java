package com.lexflow.application.review;

import com.lexflow.application.knowledge.RetrievedChunk;
import com.lexflow.domain.ai.AiAnalysisResponse;
import java.util.List;
import java.util.Objects;

/**
 * Uma pergunta jurídica respondida, como o revisor humano precisa vê-la.
 *
 * <p>Os trechos citados vêm com o **texto completo**, e não só com o identificador: o princípio da
 * seção 10 é que a pessoa veja pergunta, resposta, confiança e a fonte — nunca só o resultado final.
 *
 * @param citedChunks trechos citados, na ordem em que a IA os citou; vazio quando a resposta é
 *     determinística ou declara que a informação não está na base
 */
public record AnsweredQuestion(AiAnalysisResponse response, List<RetrievedChunk> citedChunks) {

    public AnsweredQuestion {
        Objects.requireNonNull(response, "response não pode ser nulo");
        citedChunks = List.copyOf(Objects.requireNonNull(citedChunks, "citedChunks não pode ser nulo"));
    }

    /** Enunciado da pergunta em português, como a área jurídica a faz. */
    public String statement() {
        return response.questionKey().statement();
    }

    /**
     * Indica se algum trecho citado não foi encontrado na base.
     *
     * <p>Acontece quando a fonte foi reindexada depois da resposta. O revisor precisa saber: a
     * citação existe, mas o texto que a sustentava mudou.
     */
    public boolean hasMissingCitations() {
        return citedChunks.size() < response.citedChunks().size();
    }
}
