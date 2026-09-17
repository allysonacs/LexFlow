package com.lexflow.api.review;

import com.lexflow.application.review.AnsweredQuestion;
import java.time.Instant;
import java.util.List;

/**
 * Uma pergunta jurídica respondida, no contrato REST.
 *
 * @param answerSource {@code LLM} ou {@code DETERMINISTIC}: o revisor precisa saber quando a resposta
 *     veio de uma regra de código, e não do modelo
 * @param verificationStatus resultado da segunda checagem (Prompt 14)
 * @param missingCitations {@code true} quando algum trecho citado não está mais na base normativa
 */
public record AnsweredQuestionResponse(
        String questionKey,
        String question,
        String answer,
        double confidenceScore,
        String answerSource,
        String verificationStatus,
        String verificationNotes,
        String modelVersion,
        Instant createdAt,
        boolean missingCitations,
        List<CitedChunkResponse> citedChunks) {

    public static AnsweredQuestionResponse from(AnsweredQuestion answered) {
        return new AnsweredQuestionResponse(
                answered.response().questionKey().name(),
                answered.statement(),
                answered.response().answerText(),
                answered.response().confidenceScore().value(),
                answered.response().answerSource().name(),
                answered.response().verificationStatus().name(),
                answered.response().verificationNotes(),
                answered.response().modelVersion(),
                answered.response().createdAt(),
                answered.hasMissingCitations(),
                answered.citedChunks().stream().map(CitedChunkResponse::from).toList());
    }
}
