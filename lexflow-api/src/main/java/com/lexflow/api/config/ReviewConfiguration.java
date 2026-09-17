package com.lexflow.api.config;

import com.lexflow.application.alert.LegalCaseAlertRepository;
import com.lexflow.application.analysis.AiAnalysisResponseRepository;
import com.lexflow.application.document.DocumentRepository;
import com.lexflow.application.document.DocumentStoragePort;
import com.lexflow.application.document.StoreDocumentsService;
import com.lexflow.application.idempotency.IdempotentOperationStore;
import com.lexflow.application.knowledge.KnowledgeBaseRetriever;
import com.lexflow.application.legalcase.LegalCaseReceivedEventPublisher;
import com.lexflow.application.legalcase.LegalCaseRepository;
import com.lexflow.application.legalcase.LegalCaseStatusHistoryRepository;
import com.lexflow.application.legalcase.LegalCaseStatusTransitionService;
import com.lexflow.application.review.DecisionRegisteredEventPublisher;
import com.lexflow.application.review.DecisionRepository;
import com.lexflow.application.review.FindLegalCaseAnalysisService;
import com.lexflow.application.review.RegisterDecisionService;
import com.lexflow.application.review.ResubmitDocumentationService;
import com.lexflow.application.transaction.TransactionRunner;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Montagem da revisão humana (Prompt 15).
 *
 * <p>Segue o mesmo princípio das demais configurações: as classes de {@code lexflow-application} não
 * têm anotação do Spring, e é aqui, na borda, que elas são instanciadas e ligadas aos adapters.
 */
@Configuration(proxyBeanMethods = false)
public class ReviewConfiguration {

    /** Envio de arquivos ao storage, compartilhado pela ingestão e pelo reenvio de documentação. */
    @Bean
    public StoreDocumentsService storeDocumentsService(DocumentStoragePort documentStorage) {
        return new StoreDocumentsService(documentStorage, UUID::randomUUID);
    }

    @Bean
    public FindLegalCaseAnalysisService findLegalCaseAnalysisService(
            LegalCaseRepository legalCaseRepository,
            AiAnalysisResponseRepository responseRepository,
            LegalCaseAlertRepository alertRepository,
            DecisionRepository decisionRepository,
            KnowledgeBaseRetriever knowledgeBaseRetriever) {
        return new FindLegalCaseAnalysisService(
                legalCaseRepository, responseRepository, alertRepository, decisionRepository, knowledgeBaseRetriever);
    }

    @Bean
    public RegisterDecisionService registerDecisionService(
            LegalCaseRepository legalCaseRepository,
            DecisionRepository decisionRepository,
            LegalCaseStatusTransitionService statusTransitionService,
            LegalCaseStatusHistoryRepository statusHistoryRepository,
            IdempotentOperationStore idempotencyStore,
            DecisionRegisteredEventPublisher eventPublisher,
            TransactionRunner transactionRunner,
            Clock clock) {
        return new RegisterDecisionService(
                legalCaseRepository,
                decisionRepository,
                statusTransitionService,
                statusHistoryRepository,
                idempotencyStore,
                eventPublisher,
                transactionRunner,
                clock,
                UUID::randomUUID);
    }

    @Bean
    public ResubmitDocumentationService resubmitDocumentationService(
            LegalCaseRepository legalCaseRepository,
            DocumentRepository documentRepository,
            AiAnalysisResponseRepository responseRepository,
            LegalCaseAlertRepository alertRepository,
            LegalCaseStatusTransitionService statusTransitionService,
            LegalCaseStatusHistoryRepository statusHistoryRepository,
            StoreDocumentsService storeDocumentsService,
            LegalCaseReceivedEventPublisher eventPublisher,
            TransactionRunner transactionRunner,
            Clock clock) {
        return new ResubmitDocumentationService(
                legalCaseRepository,
                documentRepository,
                responseRepository,
                alertRepository,
                statusTransitionService,
                statusHistoryRepository,
                storeDocumentsService,
                eventPublisher,
                transactionRunner,
                clock,
                UUID::randomUUID);
    }
}
