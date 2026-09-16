package com.lexflow.api.config;

import com.lexflow.application.document.DocumentRepository;
import com.lexflow.application.document.DocumentStoragePort;
import com.lexflow.application.document.DocumentTextContentRepository;
import com.lexflow.application.document.DocumentTextExtractor;
import com.lexflow.application.document.ExtractDocumentTextService;
import com.lexflow.application.event.ProcessingEventStore;
import com.lexflow.application.legalcase.FindLegalCaseService;
import com.lexflow.application.legalcase.LegalCaseIngestionIdempotencyStore;
import com.lexflow.application.legalcase.LegalCaseReceivedEventPublisher;
import com.lexflow.application.legalcase.LegalCaseRepository;
import com.lexflow.application.legalcase.LegalCaseStatusHistoryRepository;
import com.lexflow.application.legalcase.LegalCaseStatusTransitionService;
import com.lexflow.application.legalcase.ProcessLegalCaseReceivedEventService;
import com.lexflow.application.legalcase.ReceiveLegalCaseService;
import com.lexflow.application.transaction.TransactionRunner;
import com.lexflow.domain.classification.LegalCaseKeywordClassifier;
import com.lexflow.domain.legalcase.LegalCaseStatusTransitionRules;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Montagem dos casos de uso da demanda jurídica.
 *
 * <p>As classes de {@code lexflow-application} não têm anotação nenhuma do Spring, como exige a
 * seção 7 da base de conhecimento — logo elas não são descobertas pelo component scan. É aqui, na
 * borda da aplicação, que elas são instanciadas e ligadas aos adapters. O preço é este arquivo; o
 * ganho é uma camada de aplicação que se testa com um construtor e nenhum contexto de framework.
 */
@Configuration(proxyBeanMethods = false)
public class LegalCaseUseCaseConfiguration {

    /** Ponto único de mudança de status (Prompt 04). */
    @Bean
    public LegalCaseStatusTransitionService legalCaseStatusTransitionService(Clock clock) {
        return new LegalCaseStatusTransitionService(new LegalCaseStatusTransitionRules(), clock, UUID::randomUUID);
    }

    @Bean
    public ReceiveLegalCaseService receiveLegalCaseService(
            LegalCaseRepository legalCaseRepository,
            DocumentRepository documentRepository,
            LegalCaseStatusHistoryRepository statusHistoryRepository,
            LegalCaseStatusTransitionService statusTransitionService,
            DocumentStoragePort documentStorage,
            LegalCaseIngestionIdempotencyStore idempotencyStore,
            LegalCaseReceivedEventPublisher eventPublisher,
            TransactionRunner transactionRunner,
            Clock clock) {
        return new ReceiveLegalCaseService(
                legalCaseRepository,
                documentRepository,
                statusHistoryRepository,
                statusTransitionService,
                documentStorage,
                idempotencyStore,
                eventPublisher,
                transactionRunner,
                clock,
                UUID::randomUUID);
    }

    /** Classificação determinística do tipo de demanda (Prompt 08), com a tabela padrão. */
    @Bean
    public LegalCaseKeywordClassifier legalCaseKeywordClassifier() {
        return new LegalCaseKeywordClassifier();
    }

    /** Extração de texto dos documentos, nativa ou por OCR (Prompt 08). */
    @Bean
    public ExtractDocumentTextService extractDocumentTextService(
            DocumentRepository documentRepository,
            DocumentTextContentRepository textContentRepository,
            DocumentStoragePort documentStorage,
            DocumentTextExtractor textExtractor,
            Clock clock) {
        return new ExtractDocumentTextService(
                documentRepository, textContentRepository, documentStorage, textExtractor, clock, UUID::randomUUID);
    }

    /** Caso de uso disparado pelo consumo da fila (Prompts 07 e 08). */
    @Bean
    public ProcessLegalCaseReceivedEventService processLegalCaseReceivedEventService(
            LegalCaseRepository legalCaseRepository,
            DocumentRepository documentRepository,
            LegalCaseStatusHistoryRepository statusHistoryRepository,
            LegalCaseStatusTransitionService statusTransitionService,
            LegalCaseKeywordClassifier classifier,
            ExtractDocumentTextService extractDocumentTextService,
            ProcessingEventStore processingEventStore,
            TransactionRunner transactionRunner) {
        return new ProcessLegalCaseReceivedEventService(
                legalCaseRepository,
                documentRepository,
                statusHistoryRepository,
                statusTransitionService,
                classifier,
                extractDocumentTextService,
                processingEventStore,
                transactionRunner);
    }

    @Bean
    public FindLegalCaseService findLegalCaseService(
            LegalCaseRepository legalCaseRepository, DocumentRepository documentRepository) {
        return new FindLegalCaseService(legalCaseRepository, documentRepository);
    }
}
