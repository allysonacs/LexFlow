package com.lexflow.api.config;

import com.lexflow.application.document.DocumentRepository;
import com.lexflow.application.event.ProcessingEventStore;
import com.lexflow.application.document.DocumentStoragePort;
import com.lexflow.application.legalcase.FindLegalCaseService;
import com.lexflow.application.legalcase.LegalCaseIngestionIdempotencyStore;
import com.lexflow.application.legalcase.LegalCaseReceivedEventPublisher;
import com.lexflow.application.legalcase.LegalCaseRepository;
import com.lexflow.application.legalcase.LegalCaseStatusHistoryRepository;
import com.lexflow.application.legalcase.LegalCaseStatusTransitionService;
import com.lexflow.application.legalcase.ProcessLegalCaseReceivedEventService;
import com.lexflow.application.legalcase.ReceiveLegalCaseService;
import com.lexflow.application.transaction.TransactionRunner;
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

    /** Caso de uso disparado pelo consumo da fila (Prompt 07). */
    @Bean
    public ProcessLegalCaseReceivedEventService processLegalCaseReceivedEventService(
            LegalCaseRepository legalCaseRepository,
            LegalCaseStatusHistoryRepository statusHistoryRepository,
            LegalCaseStatusTransitionService statusTransitionService,
            ProcessingEventStore processingEventStore,
            TransactionRunner transactionRunner) {
        return new ProcessLegalCaseReceivedEventService(
                legalCaseRepository,
                statusHistoryRepository,
                statusTransitionService,
                processingEventStore,
                transactionRunner);
    }

    @Bean
    public FindLegalCaseService findLegalCaseService(
            LegalCaseRepository legalCaseRepository, DocumentRepository documentRepository) {
        return new FindLegalCaseService(legalCaseRepository, documentRepository);
    }
}
