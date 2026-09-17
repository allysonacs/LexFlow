package com.lexflow.application.review;

import com.lexflow.application.alert.LegalCaseAlertRepository;
import com.lexflow.application.analysis.AiAnalysisResponseRepository;
import com.lexflow.application.knowledge.KnowledgeBaseRetriever;
import com.lexflow.application.legalcase.LegalCaseRepository;
import com.lexflow.application.pagination.PageQuery;
import com.lexflow.application.pagination.PageResult;
import com.lexflow.domain.ai.AiAnalysisResponse;
import com.lexflow.domain.alert.LegalCaseAlert;
import com.lexflow.domain.exception.LegalCaseNotFoundException;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Apresenta ao revisor humano o que a IA respondeu (Prompt 15, itens 1 e 2).
 *
 * <p>Cada resposta vem com o **texto completo** dos trechos citados, e não só com os identificadores:
 * a promessa da seção 10 é que a pessoa veja a fonte, e não apenas a conclusão. Buscar esse texto
 * aqui, e não no controller, mantém a regra em um lugar só, seja qual for o ponto de entrada.
 */
public class FindLegalCaseAnalysisService {

    private final LegalCaseRepository legalCaseRepository;
    private final AiAnalysisResponseRepository responseRepository;
    private final LegalCaseAlertRepository alertRepository;
    private final DecisionRepository decisionRepository;
    private final KnowledgeBaseRetriever retriever;

    public FindLegalCaseAnalysisService(
            LegalCaseRepository legalCaseRepository,
            AiAnalysisResponseRepository responseRepository,
            LegalCaseAlertRepository alertRepository,
            DecisionRepository decisionRepository,
            KnowledgeBaseRetriever retriever) {
        this.legalCaseRepository = Objects.requireNonNull(legalCaseRepository, "legalCaseRepository não pode ser nulo");
        this.responseRepository = Objects.requireNonNull(responseRepository, "responseRepository não pode ser nulo");
        this.alertRepository = Objects.requireNonNull(alertRepository, "alertRepository não pode ser nulo");
        this.decisionRepository = Objects.requireNonNull(decisionRepository, "decisionRepository não pode ser nulo");
        this.retriever = Objects.requireNonNull(retriever, "retriever não pode ser nulo");
    }

    /**
     * Listagem paginada das demandas, para a fila de revisão humana.
     *
     * @param status filtro opcional; a fila de revisão usa {@code PENDING_HUMAN_REVIEW}
     */
    public PageResult<LegalCase> list(LegalCaseStatus status, PageQuery pageQuery) {
        return legalCaseRepository.findAll(status, pageQuery);
    }

    /**
     * Respostas da IA de uma demanda, com o texto das fontes citadas.
     *
     * @throws LegalCaseNotFoundException se a demanda não existir
     */
    public LegalCaseAnalysisView findByLegalCaseId(UUID legalCaseId) {
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        LegalCase legalCase = legalCaseRepository
                .findById(legalCaseId)
                .orElseThrow(() -> new LegalCaseNotFoundException(legalCaseId));

        List<AnsweredQuestion> questions = new ArrayList<>();
        for (AiAnalysisResponse response : responseRepository.findByLegalCaseId(legalCaseId)) {
            questions.add(new AnsweredQuestion(response, retriever.findCited(response.citedChunks())));
        }
        List<LegalCaseAlert> openAlerts = alertRepository.findByLegalCaseId(legalCaseId).stream()
                .filter(LegalCaseAlert::isOpen)
                .toList();
        return new LegalCaseAnalysisView(
                legalCase, questions, openAlerts, decisionRepository.findByLegalCaseId(legalCaseId));
    }
}
