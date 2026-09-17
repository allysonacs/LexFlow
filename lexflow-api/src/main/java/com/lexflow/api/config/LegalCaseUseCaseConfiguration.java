package com.lexflow.api.config;

import com.lexflow.application.alert.LegalCaseAlertRepository;
import com.lexflow.application.analysis.AiAnalysisResponseRepository;
import com.lexflow.application.analysis.AnalyzeLegalCaseUseCase;
import com.lexflow.application.analysis.LegalAnalysisAnswerReader;
import com.lexflow.application.checklist.ChecklistRuleRepository;
import com.lexflow.application.checklist.DocumentChecklistItemRepository;
import com.lexflow.application.checklist.DocumentChecklistService;
import com.lexflow.application.checklist.ManageChecklistRulesService;
import com.lexflow.application.document.DocumentRepository;
import com.lexflow.application.document.DocumentStoragePort;
import com.lexflow.application.document.DocumentTextContentRepository;
import com.lexflow.application.document.DocumentTextExtractor;
import com.lexflow.application.document.ExtractDocumentTextService;
import com.lexflow.application.event.ProcessingEventStore;
import com.lexflow.application.fact.AiExtractedFactRepository;
import com.lexflow.application.fact.ExtractLegalFactsUseCase;
import com.lexflow.application.knowledge.KnowledgeBaseRetriever;
import com.lexflow.application.llm.LlmClientPort;
import com.lexflow.application.llm.StructuredOutputValidator;
import com.lexflow.application.prompt.PromptVersionRepository;
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
import org.springframework.beans.factory.annotation.Value;
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

    /** Checklist documental determinístico (Prompt 09). */
    @Bean
    public DocumentChecklistService documentChecklistService(
            LegalCaseRepository legalCaseRepository,
            DocumentRepository documentRepository,
            ChecklistRuleRepository ruleRepository,
            DocumentChecklistItemRepository itemRepository,
            Clock clock) {
        return new DocumentChecklistService(
                legalCaseRepository, documentRepository, ruleRepository, itemRepository, clock, UUID::randomUUID);
    }

    /** Administração das regras de checklist (Prompt 09). */
    @Bean
    public ManageChecklistRulesService manageChecklistRulesService(
            ChecklistRuleRepository ruleRepository,
            DocumentChecklistItemRepository itemRepository,
            TransactionRunner transactionRunner) {
        return new ManageChecklistRulesService(ruleRepository, itemRepository, transactionRunner, UUID::randomUUID);
    }

    /**
     * Extração estruturada de fatos via LLM (Prompt 11).
     *
     * @param maxDocumentCharacters documentos maiores recebem alerta em vez de serem enviados; o
     *     padrão, cerca de 400 mil caracteres, fica bem abaixo da janela de contexto do modelo
     */
    @Bean
    public ExtractLegalFactsUseCase extractLegalFactsUseCase(
            LegalCaseRepository legalCaseRepository,
            DocumentRepository documentRepository,
            DocumentTextContentRepository textContentRepository,
            AiExtractedFactRepository factRepository,
            LegalCaseAlertRepository alertRepository,
            PromptVersionRepository promptVersionRepository,
            LlmClientPort llmClient,
            StructuredOutputValidator structuredOutputValidator,
            LegalCaseStatusTransitionService statusTransitionService,
            LegalCaseStatusHistoryRepository statusHistoryRepository,
            TransactionRunner transactionRunner,
            Clock clock,
            @Value("${lexflow.pipeline.fact-extraction.max-document-characters:400000}") int maxDocumentCharacters) {
        return new ExtractLegalFactsUseCase(
                legalCaseRepository,
                documentRepository,
                textContentRepository,
                factRepository,
                alertRepository,
                promptVersionRepository,
                llmClient,
                structuredOutputValidator,
                statusTransitionService,
                statusHistoryRepository,
                transactionRunner,
                clock,
                UUID::randomUUID,
                maxDocumentCharacters);
    }

    /**
     * Cadeia de prompts que responde às perguntas jurídicas da demanda (Prompt 13).
     *
     * <p>É o componente mais crítico do sistema: é dele que saem as respostas que o revisor humano lê.
     * Toda resposta gravada traz a origem, e as vindas do modelo trazem também o modelo e a versão de
     * prompt usados.
     */
    @Bean
    public AnalyzeLegalCaseUseCase analyzeLegalCaseUseCase(
            LegalCaseRepository legalCaseRepository,
            DocumentRepository documentRepository,
            AiExtractedFactRepository factRepository,
            DocumentChecklistService checklistService,
            KnowledgeBaseRetriever knowledgeBaseRetriever,
            AiAnalysisResponseRepository responseRepository,
            LegalCaseAlertRepository alertRepository,
            PromptVersionRepository promptVersionRepository,
            LlmClientPort llmClient,
            StructuredOutputValidator structuredOutputValidator,
            LegalAnalysisAnswerReader answerReader,
            LegalCaseStatusTransitionService statusTransitionService,
            LegalCaseStatusHistoryRepository statusHistoryRepository,
            TransactionRunner transactionRunner,
            Clock clock) {
        return new AnalyzeLegalCaseUseCase(
                legalCaseRepository,
                documentRepository,
                factRepository,
                checklistService,
                knowledgeBaseRetriever,
                responseRepository,
                alertRepository,
                promptVersionRepository,
                llmClient,
                structuredOutputValidator,
                answerReader,
                statusTransitionService,
                statusHistoryRepository,
                transactionRunner,
                clock,
                UUID::randomUUID);
    }

    /**
     * Caso de uso disparado pelo consumo da fila (Prompts 07 a 13).
     *
     * @param factExtractionEnabled desligar faz a demanda parar em {@code EXTRACTING}, sem chamar o
     *     LLM — útil em ambientes sem chave de API
     * @param legalAnalysisEnabled desligar faz a demanda parar em {@code AI_ANALYSIS_IN_PROGRESS},
     *     sem consultar a base normativa nem o LLM
     */
    @Bean
    public ProcessLegalCaseReceivedEventService processLegalCaseReceivedEventService(
            LegalCaseRepository legalCaseRepository,
            DocumentRepository documentRepository,
            LegalCaseStatusHistoryRepository statusHistoryRepository,
            LegalCaseStatusTransitionService statusTransitionService,
            LegalCaseKeywordClassifier classifier,
            DocumentChecklistService checklistService,
            ExtractDocumentTextService extractDocumentTextService,
            ExtractLegalFactsUseCase extractLegalFactsUseCase,
            @Value("${lexflow.pipeline.fact-extraction.enabled:true}") boolean factExtractionEnabled,
            AnalyzeLegalCaseUseCase analyzeLegalCaseUseCase,
            @Value("${lexflow.pipeline.legal-analysis.enabled:true}") boolean legalAnalysisEnabled,
            ProcessingEventStore processingEventStore,
            TransactionRunner transactionRunner) {
        return new ProcessLegalCaseReceivedEventService(
                legalCaseRepository,
                documentRepository,
                statusHistoryRepository,
                statusTransitionService,
                classifier,
                checklistService,
                extractDocumentTextService,
                extractLegalFactsUseCase,
                factExtractionEnabled,
                analyzeLegalCaseUseCase,
                legalAnalysisEnabled,
                processingEventStore,
                transactionRunner);
    }

    @Bean
    public FindLegalCaseService findLegalCaseService(
            LegalCaseRepository legalCaseRepository,
            DocumentRepository documentRepository,
            LegalCaseAlertRepository alertRepository) {
        return new FindLegalCaseService(legalCaseRepository, documentRepository, alertRepository);
    }
}
