package com.lexflow.application.analysis;

import com.lexflow.domain.ai.AiAnalysisResponse;
import com.lexflow.domain.ai.QuestionKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída das respostas da IA.
 *
 * <p>O banco tem índice único em {@code (legal_case_id, question_key)}: uma resposta por pergunta em
 * cada demanda. É isso que torna a etapa retomável sem repetir chamadas já pagas.
 */
public interface AiAnalysisResponseRepository {

    AiAnalysisResponse save(AiAnalysisResponse response);

    /** Respostas de uma demanda, na ordem em que as perguntas são feitas. */
    List<AiAnalysisResponse> findByLegalCaseId(UUID legalCaseId);

    Optional<AiAnalysisResponse> findByLegalCaseIdAndQuestionKey(UUID legalCaseId, QuestionKey questionKey);

    /**
     * Apaga as respostas de uma demanda.
     *
     * <p>Usado quando a documentação é reenviada (Prompt 15): as respostas foram dadas sobre uma
     * documentação que mudou, e mantê-las faria o revisor ler uma análise que não corresponde mais
     * ao que está anexado.
     */
    void deleteByLegalCaseId(UUID legalCaseId);
}
