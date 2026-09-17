package com.lexflow.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.application.analysis.support.AnalysisTestDoubles;
import com.lexflow.application.analysis.support.AnalysisTestDoubles.InMemoryAiAnalysisResponseRepository;
import com.lexflow.application.analysis.support.AnalysisTestDoubles.SimpleLegalAnalysisAnswerReader;
import com.lexflow.application.checklist.DocumentChecklistService;
import com.lexflow.application.knowledge.IngestKnowledgeBaseSourceCommand;
import com.lexflow.application.knowledge.IngestKnowledgeBaseSourceService;
import com.lexflow.application.knowledge.KnowledgeBaseRetriever;
import com.lexflow.application.knowledge.support.KnowledgeBaseTestDoubles;
import com.lexflow.application.knowledge.support.KnowledgeBaseTestDoubles.InMemoryKnowledgeBaseChunkRepository;
import com.lexflow.application.knowledge.support.KnowledgeBaseTestDoubles.InMemoryKnowledgeBaseSourceRepository;
import com.lexflow.application.knowledge.support.KnowledgeBaseTestDoubles.LexicalEmbeddingClient;
import com.lexflow.application.legalcase.LegalCaseStatusTransitionService;
import com.lexflow.application.legalcase.support.ChecklistTestDoubles;
import com.lexflow.application.legalcase.support.ChecklistTestDoubles.InMemoryChecklistRuleRepository;
import com.lexflow.application.legalcase.support.ChecklistTestDoubles.InMemoryDocumentChecklistItemRepository;
import com.lexflow.application.legalcase.support.FactExtractionTestDoubles.FakeStructuredOutputValidator;
import com.lexflow.application.legalcase.support.FactExtractionTestDoubles.InMemoryAiExtractedFactRepository;
import com.lexflow.application.legalcase.support.FactExtractionTestDoubles.InMemoryLegalCaseAlertRepository;
import com.lexflow.application.legalcase.support.FactExtractionTestDoubles.InMemoryPromptVersionRepository;
import com.lexflow.application.legalcase.support.FactExtractionTestDoubles.ScriptedLlmClient;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.DirectTransactionRunner;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryDocumentRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryLegalCaseRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryStatusHistoryRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.SequentialIdGenerator;
import com.lexflow.application.llm.LlmRefusalException;
import com.lexflow.application.llm.LlmRequest;
import com.lexflow.application.prompt.PromptVersionNotFoundException;
import com.lexflow.domain.ai.AiAnalysisResponse;
import com.lexflow.domain.ai.AiExtractedFact;
import com.lexflow.domain.ai.AnswerSource;
import com.lexflow.domain.ai.QuestionKey;
import com.lexflow.domain.alert.LegalCaseAlertType;
import com.lexflow.domain.document.Document;
import com.lexflow.domain.document.Sha256Checksum;
import com.lexflow.domain.exception.InvalidStatusTransitionException;
import com.lexflow.domain.exception.LegalCaseNotFoundException;
import com.lexflow.domain.knowledge.ChunkingPolicy;
import com.lexflow.domain.knowledge.KnowledgeBaseChunk;
import com.lexflow.domain.knowledge.KnowledgeBaseSourceType;
import com.lexflow.domain.knowledge.TextChunker;
import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseStatusTransitionRules;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cadeia de prompts com LLM roteirizado e base normativa em memória — nenhuma chamada externa.
 *
 * <p>O que estes testes fixam é o contrato do componente mais crítico do sistema: toda resposta
 * gravada é rastreável, nenhuma cita trecho que não foi fornecido e a demanda só chega ao revisor
 * humano quando todas as perguntas foram respondidas.
 */
class AnalyzeLegalCaseUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    private static final String ALCADAS =
            """
            Art. 1º A assinatura de contratos com valor superior a cem mil reais depende de aprovação prévia do diretor jurídico.

            Art. 2º Contratos de valor igual ou inferior a cem mil reais podem ser assinados pelo gerente da área.
            """;

    private InMemoryLegalCaseRepository legalCaseRepository;
    private InMemoryDocumentRepository documentRepository;
    private InMemoryAiExtractedFactRepository factRepository;
    private InMemoryChecklistRuleRepository ruleRepository;
    private InMemoryDocumentChecklistItemRepository checklistItemRepository;
    private InMemoryKnowledgeBaseSourceRepository sourceRepository;
    private InMemoryKnowledgeBaseChunkRepository chunkRepository;
    private InMemoryAiAnalysisResponseRepository responseRepository;
    private InMemoryLegalCaseAlertRepository alertRepository;
    private InMemoryPromptVersionRepository promptRepository;
    private InMemoryStatusHistoryRepository historyRepository;
    private ScriptedLlmClient llm;
    private FakeStructuredOutputValidator validator;
    private DocumentChecklistService checklistService;
    private AnalyzeLegalCaseUseCase useCase;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SequentialIdGenerator ids = new SequentialIdGenerator();
        legalCaseRepository = new InMemoryLegalCaseRepository();
        documentRepository = new InMemoryDocumentRepository();
        factRepository = new InMemoryAiExtractedFactRepository();
        ruleRepository = new InMemoryChecklistRuleRepository();
        ChecklistTestDoubles.seedRules().forEach(ruleRepository::save);
        checklistItemRepository = new InMemoryDocumentChecklistItemRepository();
        sourceRepository = new InMemoryKnowledgeBaseSourceRepository();
        chunkRepository = new InMemoryKnowledgeBaseChunkRepository(sourceRepository);
        responseRepository = new InMemoryAiAnalysisResponseRepository();
        alertRepository = new InMemoryLegalCaseAlertRepository();
        promptRepository = new InMemoryPromptVersionRepository().with(AnalysisTestDoubles.PROMPT_V1);
        historyRepository = new InMemoryStatusHistoryRepository();
        llm = new ScriptedLlmClient();
        validator = new FakeStructuredOutputValidator();
        checklistService = new DocumentChecklistService(
                legalCaseRepository, documentRepository, ruleRepository, checklistItemRepository, clock, ids);
        useCase = new AnalyzeLegalCaseUseCase(
                legalCaseRepository,
                documentRepository,
                factRepository,
                checklistService,
                new KnowledgeBaseRetriever(new LexicalEmbeddingClient(), chunkRepository, 3, 0.0),
                responseRepository,
                alertRepository,
                promptRepository,
                llm,
                validator,
                new SimpleLegalAnalysisAnswerReader(),
                new LegalCaseStatusTransitionService(new LegalCaseStatusTransitionRules(), clock, ids),
                historyRepository,
                new DirectTransactionRunner(),
                clock,
                ids);
    }

    @Test
    @DisplayName("critério de aceite: toda pergunta aplicável é respondida e a demanda vai para revisão humana")
    void shouldAnswerEveryApplicableQuestionAndAdvance() {
        LegalCase legalCase = givenCaseWithCompleteDocumentation();
        givenKnowledgeBase();
        llm.byDefault(request -> ScriptedLlmClient.response(
                AnalysisTestDoubles.answerJson(questionOf(request), "Sim, desde que aprovado pelo diretor.", firstChunkId())));

        LegalCaseAnalysisResult result = useCase.analyze(legalCase.id());

        assertThat(result.advanced()).isTrue();
        assertThat(result.responses()).hasSize(3);
        assertThat(result.responses()).extracting(AiAnalysisResponse::questionKey)
                .containsExactlyInAnyOrderElementsOf(QuestionKey.applicableTo(LegalCaseType.CONTRACT_SIGNING));
        assertThat(result.llmCalls()).isEqualTo(3);
        assertThat(legalCaseRepository.findById(legalCase.id()).orElseThrow().status())
                .isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW);
        assertThat(historyRepository.findByLegalCaseId(legalCase.id()))
                .anySatisfy(entry -> assertThat(entry.reason()).contains("Análise da IA concluída"));
    }

    @Test
    @DisplayName("toda resposta do modelo é rastreável: origem, modelo e versão de prompt")
    void shouldPersistTraceabilityForEveryLlmAnswer() {
        LegalCase legalCase = givenCaseWithCompleteDocumentation();
        givenKnowledgeBase();
        llm.byDefault(request -> ScriptedLlmClient.response(
                AnalysisTestDoubles.answerJson(questionOf(request), "Resposta fundamentada.", firstChunkId())));

        useCase.analyze(legalCase.id());

        assertThat(responseRepository.findByLegalCaseId(legalCase.id())).allSatisfy(response -> {
            assertThat(response.answerSource()).isEqualTo(AnswerSource.LLM);
            assertThat(response.modelVersion()).isEqualTo("claude-opus-5");
            assertThat(response.promptVersionId()).isEqualTo(AnalysisTestDoubles.PROMPT_V1.id());
            assertThat(response.citedChunks()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("com documentação incompleta, a suficiência é respondida por código, sem chamar o LLM")
    void shouldAnswerSufficiencyDeterministicallyWhenDocumentationIsIncomplete() {
        LegalCase legalCase = givenCase(LegalCaseStatus.AI_ANALYSIS_IN_PROGRESS);
        givenDocument(legalCase, "contrato.pdf", "CONTRACT_DRAFT");
        checklistService.synchronize(legalCase);
        givenKnowledgeBase();
        llm.byDefault(request -> ScriptedLlmClient.response(
                AnalysisTestDoubles.answerJson(questionOf(request), "Resposta fundamentada.", firstChunkId())));

        LegalCaseAnalysisResult result = useCase.analyze(legalCase.id());

        AiAnalysisResponse sufficiency = responseRepository
                .findByLegalCaseIdAndQuestionKey(legalCase.id(), QuestionKey.HAS_SUFFICIENT_DOCUMENTATION)
                .orElseThrow();
        assertThat(sufficiency.answerSource()).isEqualTo(AnswerSource.DETERMINISTIC);
        assertThat(sufficiency.answerText()).contains("Documentação incompleta", "FINANCIAL_OPINION");
        assertThat(sufficiency.modelVersion()).isNull();
        // As outras duas perguntas continuam indo ao modelo.
        assertThat(result.llmCalls()).isEqualTo(2);
        assertThat(result.deterministicAnswerCount()).isEqualTo(1);
        assertThat(result.advanced()).isTrue();
        assertThat(llm.requests()).noneSatisfy(request ->
                assertThat(request.prompt()).contains(QuestionKey.HAS_SUFFICIENT_DOCUMENTATION.name()));
    }

    @Test
    @DisplayName("sem nada próximo na base normativa, a resposta declara isso em vez de chamar o modelo")
    void shouldAnswerNotFoundWhenTheKnowledgeBaseHasNothingRelevant() {
        LegalCase legalCase = givenCaseWithCompleteDocumentation();

        LegalCaseAnalysisResult result = useCase.analyze(legalCase.id());

        assertThat(result.llmCalls()).isZero();
        assertThat(llm.requests()).isEmpty();
        assertThat(result.responses()).hasSize(3);
        assertThat(result.responses()).allSatisfy(response -> {
            assertThat(response.answerSource()).isEqualTo(AnswerSource.DETERMINISTIC);
            assertThat(response.citedChunks()).isEmpty();
        });
        assertThat(responseRepository
                        .findByLegalCaseIdAndQuestionKey(legalCase.id(), QuestionKey.CAN_SIGN_CONTRACT)
                        .orElseThrow()
                        .declaresNotFound())
                .isTrue();
        assertThat(result.advanced()).isTrue();
    }

    @Test
    @DisplayName("um trecho citado que não foi fornecido é recusado, e a segunda tentativa é reforçada")
    void shouldRejectCitationOfChunksThatWereNotProvided() {
        LegalCase legalCase = givenCaseWithCompleteDocumentation();
        givenKnowledgeBase();
        UUID inventado = UUID.randomUUID();
        llm.then(request -> ScriptedLlmClient.response(
                        AnalysisTestDoubles.answerJson(questionOf(request), "Sim.", List.of(inventado))))
                .byDefault(request -> ScriptedLlmClient.response(
                        AnalysisTestDoubles.answerJson(questionOf(request), "Sim.", firstChunkId())));

        LegalCaseAnalysisResult result = useCase.analyze(legalCase.id());

        assertThat(result.advanced()).isTrue();
        assertThat(llm.requests().get(1).prompt())
                .contains("A resposta anterior não foi aceita", "trecho citado fora da lista fornecida");
        assertThat(responseRepository
                        .findByLegalCaseIdAndQuestionKey(legalCase.id(), QuestionKey.CAN_SIGN_CONTRACT)
                        .orElseThrow()
                        .citedChunks())
                .doesNotContain(inventado);
    }

    @Test
    @DisplayName("resposta inválida duas vezes vira alerta, nada é gravado e a demanda não avança")
    void shouldOpenAlertWhenTheAnswerIsInvalidTwice() {
        LegalCase legalCase = givenCaseWithCompleteDocumentation();
        givenKnowledgeBase();
        UUID inventado = UUID.randomUUID();
        llm.byDefault(request -> ScriptedLlmClient.response(
                AnalysisTestDoubles.answerJson(questionOf(request), "Sim.", List.of(inventado))));

        LegalCaseAnalysisResult result = useCase.analyze(legalCase.id());

        assertThat(result.advanced()).isFalse();
        assertThat(result.llmCalls()).isEqualTo(2);
        assertThat(responseRepository.findByLegalCaseId(legalCase.id())).isEmpty();
        assertThat(alertRepository.all()).singleElement().satisfies(alert -> {
            assertThat(alert.type()).isEqualTo(LegalCaseAlertType.AI_ANALYSIS_INVALID_OUTPUT);
            assertThat(alert.message()).contains("CAN_SIGN_CONTRACT", "LEGAL_ANALYSIS v1");
        });
        assertThat(legalCaseRepository.findById(legalCase.id()).orElseThrow().status())
                .isEqualTo(LegalCaseStatus.AI_ANALYSIS_IN_PROGRESS);
    }

    @Test
    @DisplayName("com alerta em aberto, uma nova execução não gasta chamada nenhuma")
    void shouldNotCallTheModelAgainWhileAnAlertIsOpen() {
        LegalCase legalCase = givenCaseWithCompleteDocumentation();
        givenKnowledgeBase();
        llm.byDefault(request -> ScriptedLlmClient.response(
                AnalysisTestDoubles.answerJson(questionOf(request), "Sim.", List.of(UUID.randomUUID()))));
        useCase.analyze(legalCase.id());
        int callsBefore = llm.requests().size();

        LegalCaseAnalysisResult retry = useCase.analyze(legalCase.id());

        assertThat(llm.requests()).hasSize(callsBefore);
        assertThat(retry.llmCalls()).isZero();
        assertThat(retry.advanced()).isFalse();
        assertThat(retry.openAlerts()).hasSize(1);
    }

    @Test
    @DisplayName("a recusa do modelo vira alerta, sem gravar resposta")
    void shouldOpenAlertWhenTheModelRefuses() {
        LegalCase legalCase = givenCaseWithCompleteDocumentation();
        givenKnowledgeBase();
        llm.byDefault(request -> {
            throw new LlmRefusalException("claude-opus-5", "legal_advice");
        });

        LegalCaseAnalysisResult result = useCase.analyze(legalCase.id());

        assertThat(result.advanced()).isFalse();
        assertThat(responseRepository.findByLegalCaseId(legalCase.id())).isEmpty();
        assertThat(alertRepository.all()).singleElement().satisfies(alert -> assertThat(alert.type())
                .isEqualTo(LegalCaseAlertType.AI_ANALYSIS_REFUSED));
    }

    @Test
    @DisplayName("os alertas do modelo acompanham a resposta, para o revisor não deixar de lê-los")
    void shouldKeepModelAlertsNextToTheAnswer() {
        LegalCase legalCase = givenCaseWithCompleteDocumentation();
        givenKnowledgeBase();
        llm.byDefault(request -> ScriptedLlmClient.response(AnalysisTestDoubles.answerJson(
                questionOf(request),
                "Sim, com ressalvas.",
                0.6,
                firstChunkId(),
                List.of("O valor do contrato não consta dos documentos."))));

        useCase.analyze(legalCase.id());

        assertThat(responseRepository
                        .findByLegalCaseIdAndQuestionKey(legalCase.id(), QuestionKey.CAN_SIGN_CONTRACT)
                        .orElseThrow()
                        .answerText())
                .contains("Sim, com ressalvas.", "Pontos para verificação:", "O valor do contrato não consta");
    }

    @Test
    @DisplayName("perguntas já respondidas não voltam ao modelo")
    void shouldNotAskAgainForQuestionsAlreadyAnswered() {
        LegalCase legalCase = givenCaseWithCompleteDocumentation();
        givenKnowledgeBase();
        llm.byDefault(request -> ScriptedLlmClient.response(
                AnalysisTestDoubles.answerJson(questionOf(request), "Sim.", firstChunkId())));
        useCase.analyze(legalCase.id());
        int callsBefore = llm.requests().size();

        // A demanda já está em PENDING_HUMAN_REVIEW: a etapa se considera concluída.
        LegalCaseAnalysisResult retry = useCase.analyze(legalCase.id());

        assertThat(llm.requests()).hasSize(callsBefore);
        assertThat(retry.responses()).hasSize(3);
        assertThat(retry.advanced()).isFalse();
    }

    @Test
    @DisplayName("uma demanda fora da etapa de análise, inexistente ou sem prompt ativo é recusada")
    void shouldRejectCasesOutsideTheAnalysisStage() {
        LegalCase extracting = givenCase(LegalCaseStatus.EXTRACTING);

        assertThatExceptionOfType(InvalidStatusTransitionException.class)
                .isThrownBy(() -> useCase.analyze(extracting.id()));
        assertThatExceptionOfType(LegalCaseNotFoundException.class)
                .isThrownBy(() -> useCase.analyze(UUID.randomUUID()));

        LegalCase legalCase = givenCaseWithCompleteDocumentation();
        givenKnowledgeBase();
        promptRepository = new InMemoryPromptVersionRepository();
        AnalyzeLegalCaseUseCase semPrompt = useCaseWith(promptRepository);
        assertThatExceptionOfType(PromptVersionNotFoundException.class)
                .isThrownBy(() -> semPrompt.analyze(legalCase.id()));
    }

    private AnalyzeLegalCaseUseCase useCaseWith(InMemoryPromptVersionRepository prompts) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SequentialIdGenerator ids = new SequentialIdGenerator();
        return new AnalyzeLegalCaseUseCase(
                legalCaseRepository,
                documentRepository,
                factRepository,
                checklistService,
                new KnowledgeBaseRetriever(new LexicalEmbeddingClient(), chunkRepository, 3, 0.0),
                responseRepository,
                alertRepository,
                prompts,
                llm,
                validator,
                new SimpleLegalAnalysisAnswerReader(),
                new LegalCaseStatusTransitionService(new LegalCaseStatusTransitionRules(), clock, ids),
                historyRepository,
                new DirectTransactionRunner(),
                clock,
                ids);
    }

    private LegalCase givenCase(LegalCaseStatus status) {
        LegalCase legalCase = LegalCase.receive(
                UUID.randomUUID(), null, LegalCaseType.CONTRACT_SIGNING, "ana.silva", CasePriority.NORMAL, NOW);
        LegalCaseStatusTransitionRules anyTransition = new LegalCaseStatusTransitionRules() {
            @Override
            public boolean isAllowed(LegalCaseStatus current, LegalCaseStatus target) {
                return true;
            }
        };
        if (status != LegalCaseStatus.RECEIVED) {
            legalCase = legalCase.transitionTo(status, NOW, anyTransition);
        }
        return legalCaseRepository.save(legalCase);
    }

    /** Demanda em análise, com os dois documentos obrigatórios do tipo e fatos extraídos. */
    private LegalCase givenCaseWithCompleteDocumentation() {
        LegalCase legalCase = givenCase(LegalCaseStatus.AI_ANALYSIS_IN_PROGRESS);
        givenDocument(legalCase, "contrato.pdf", "CONTRACT_DRAFT");
        givenDocument(legalCase, "parecer.pdf", "FINANCIAL_OPINION");
        checklistService.synchronize(legalCase);
        return legalCase;
    }

    private void givenDocument(LegalCase legalCase, String fileName, String documentType) {
        Document document = new Document(
                UUID.randomUUID(),
                legalCase.id(),
                fileName,
                "legal-cases/" + fileName,
                "application/pdf",
                Sha256Checksum.ofContent(fileName.getBytes()),
                NOW,
                documentType);
        documentRepository.saveAll(List.of(document));
        factRepository.save(new AiExtractedFact(
                UUID.randomUUID(),
                legalCase.id(),
                document.id(),
                "{\"documentKind\":\"Contrato de prestação de serviços\",\"monetaryValues\":[]}",
                "claude-opus-5",
                UUID.randomUUID(),
                NOW));
    }

    private void givenKnowledgeBase() {
        new IngestKnowledgeBaseSourceService(
                        sourceRepository,
                        chunkRepository,
                        new LexicalEmbeddingClient(),
                        new TextChunker(ChunkingPolicy.DEFAULT),
                        (format, content) -> {
                            throw new UnsupportedOperationException();
                        },
                        KnowledgeBaseTestDoubles.directTransactionRunner(),
                        UUID::randomUUID)
                .ingest(new IngestKnowledgeBaseSourceCommand(
                        "Política de Alçadas", KnowledgeBaseSourceType.INTERNAL_POLICY, null, ALCADAS));
    }

    private List<UUID> firstChunkId() {
        return List.of(chunkRepository.findBySourceId(
                        sourceRepository.findAll(com.lexflow.application.pagination.PageQuery.of(0, 1))
                                .content()
                                .getFirst()
                                .id())
                .stream()
                .map(KnowledgeBaseChunk::id)
                .findFirst()
                .orElseThrow());
    }

    /** Descobre qual pergunta o prompt está fazendo, para o dublê responder a ela. */
    private static QuestionKey questionOf(LlmRequest request) {
        for (QuestionKey question : QuestionKey.values()) {
            if (request.systemPrompt().contains("(\"question_key\"): " + question.name())) {
                return question;
            }
        }
        throw new AssertionError("prompt sem pergunta identificável");
    }
}
