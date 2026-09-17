package com.lexflow.application.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.application.analysis.VerificationSummary;
import com.lexflow.application.analysis.support.AnalysisTestDoubles;
import com.lexflow.application.analysis.support.AnalysisTestDoubles.InMemoryAiAnalysisResponseRepository;
import com.lexflow.application.analysis.support.AnalysisTestDoubles.SimpleAnswerVerificationReader;
import com.lexflow.application.knowledge.IngestKnowledgeBaseSourceCommand;
import com.lexflow.application.knowledge.IngestKnowledgeBaseSourceService;
import com.lexflow.application.knowledge.KnowledgeBaseRetriever;
import com.lexflow.application.knowledge.support.KnowledgeBaseTestDoubles;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryIdempotencyStore;
import com.lexflow.application.knowledge.support.KnowledgeBaseTestDoubles.InMemoryKnowledgeBaseChunkRepository;
import com.lexflow.application.knowledge.support.KnowledgeBaseTestDoubles.InMemoryKnowledgeBaseSourceRepository;
import com.lexflow.application.knowledge.support.KnowledgeBaseTestDoubles.LexicalEmbeddingClient;
import com.lexflow.application.legalcase.support.FactExtractionTestDoubles.FakeStructuredOutputValidator;
import com.lexflow.application.legalcase.support.FactExtractionTestDoubles.InMemoryPromptVersionRepository;
import com.lexflow.application.legalcase.support.FactExtractionTestDoubles.ScriptedLlmClient;
import com.lexflow.application.llm.LlmRefusalException;
import com.lexflow.application.prompt.PromptVersionNotFoundException;
import com.lexflow.domain.ai.AiAnalysisResponse;
import com.lexflow.domain.ai.ConfidenceScore;
import com.lexflow.domain.ai.PromptVersion;
import com.lexflow.domain.ai.QuestionKey;
import com.lexflow.domain.ai.VerificationStatus;
import com.lexflow.domain.knowledge.ChunkingPolicy;
import com.lexflow.domain.knowledge.KnowledgeBaseChunk;
import com.lexflow.domain.knowledge.KnowledgeBaseSourceType;
import com.lexflow.domain.knowledge.TextChunker;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Segunda checagem com LLM roteirizado (Prompt 14).
 *
 * <p>O que estes testes fixam: uma resposta confirmada mantém a confiança, uma resposta não
 * confirmada tem a confiança zerada, e em nenhum dos casos o texto original é alterado.
 */
class VerifyAiAnalysisResponseUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    private static final String ALCADAS =
            """
            Art. 1º A assinatura de contratos com valor superior a cem mil reais depende de aprovação prévia do diretor jurídico.
            """;

    /** Cópia reduzida do template da migration {@code V8__answer_verification.sql}. */
    private static final PromptVersion PROMPT_V1 = new PromptVersion(
            UUID.fromString("7c1d0e5a-0011-4f00-8000-000000000003"),
            AnswerVerificationSchema.PROMPT_KEY,
            1,
            """
            ### SISTEMA ###
            Você confere se uma resposta jurídica é sustentada pelos trechos normativos que ela citou.
            Não reescreva a resposta e não avalie o mérito.

            Pergunta analisada: {{QUESTION_KEY}} — {{QUESTION_TEXT}}
            ### USUARIO ###
            <resposta>
            {{ANSWER}}
            </resposta>

            <trechos>
            {{CHUNKS}}
            </trechos>

            A resposta acima é sustentada pelos trechos citados?""",
            true,
            NOW);

    private static final String ANSWER_TEXT =
            "Sim, desde que o contrato seja aprovado pelo diretor jurídico antes da assinatura.";

    private InMemoryKnowledgeBaseSourceRepository sourceRepository;
    private InMemoryKnowledgeBaseChunkRepository chunkRepository;
    private InMemoryAiAnalysisResponseRepository responseRepository;
    private InMemoryPromptVersionRepository promptRepository;
    private ScriptedLlmClient llm;
    private VerifyAiAnalysisResponseUseCase useCase;
    private UUID legalCaseId;
    private UUID chunkId;

    @BeforeEach
    void setUp() {
        sourceRepository = new InMemoryKnowledgeBaseSourceRepository();
        chunkRepository = new InMemoryKnowledgeBaseChunkRepository(sourceRepository);
        responseRepository = new InMemoryAiAnalysisResponseRepository();
        promptRepository = new InMemoryPromptVersionRepository().with(PROMPT_V1);
        llm = new ScriptedLlmClient();
        legalCaseId = UUID.randomUUID();
        chunkId = givenKnowledgeBase();
        useCase = newUseCase(QuestionKey.criticalQuestions());
    }

    private VerifyAiAnalysisResponseUseCase newUseCase(Set<QuestionKey> criticalQuestions) {
        return new VerifyAiAnalysisResponseUseCase(
                responseRepository,
                new KnowledgeBaseRetriever(new LexicalEmbeddingClient(), chunkRepository, 3, 0.0),
                promptRepository,
                llm,
                new FakeStructuredOutputValidator(),
                new SimpleAnswerVerificationReader(),
                criticalQuestions);
    }

    @Test
    @DisplayName("uma resposta sustentada pelos trechos citados é marcada como verificada, sem perder confiança")
    void shouldMarkSupportedAnswerAsVerified() {
        AiAnalysisResponse original = givenCriticalAnswer(List.of(chunkId));
        llm.byDefault(request -> ScriptedLlmClient.response(
                AnalysisTestDoubles.verificationJson(true, "O trecho citado prevê a aprovação do diretor jurídico.")));

        VerificationSummary summary = useCase.verify(legalCaseId);

        AiAnalysisResponse verified = reload();
        assertThat(summary).isEqualTo(new VerificationSummary(1, 0, 0, 1));
        assertThat(verified.verificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(verified.confidenceScore()).isEqualTo(original.confidenceScore());
        assertThat(verified.answerText()).isEqualTo(ANSWER_TEXT);
        assertThat(verified.verificationNotes()).contains("aprovação do diretor jurídico", "claude-opus-5");
    }

    @Test
    @DisplayName("critério de aceite: uma resposta não sustentada é marcada como FAILED e tem a confiança zerada")
    void shouldMarkUnsupportedAnswerAsFailedAndZeroConfidence() {
        givenCriticalAnswer(List.of(chunkId));
        llm.byDefault(request -> ScriptedLlmClient.response(AnalysisTestDoubles.verificationJson(
                false, "O trecho citado trata de alçada de assinatura, não do prazo afirmado na resposta.")));

        VerificationSummary summary = useCase.verify(legalCaseId);

        AiAnalysisResponse failed = reload();
        assertThat(summary).isEqualTo(new VerificationSummary(0, 1, 0, 1));
        assertThat(failed.verificationStatus()).isEqualTo(VerificationStatus.FAILED);
        assertThat(failed.confidenceScore().value()).isZero();
        // A checagem sinaliza; ela não reescreve.
        assertThat(failed.answerText()).isEqualTo(ANSWER_TEXT);
        assertThat(failed.verificationNotes()).contains("trata de alçada de assinatura");
    }

    @Test
    @DisplayName("o prompt de verificação leva a resposta e o texto dos trechos citados")
    void shouldBuildThePromptWithTheAnswerAndTheCitedText() {
        givenCriticalAnswer(List.of(chunkId));
        llm.byDefault(request -> ScriptedLlmClient.response(AnalysisTestDoubles.verificationJson(true, "Sustentada.")));

        useCase.verify(legalCaseId);

        assertThat(llm.requests()).singleElement().satisfies(request -> {
            assertThat(request.prompt())
                    .contains("<resposta>", ANSWER_TEXT, "<trechos>", chunkId.toString(), "diretor jurídico");
            assertThat(request.systemPrompt()).contains("CAN_SIGN_CONTRACT", "Não reescreva a resposta");
            assertThat(request.outputSchema()).isEqualTo(AnswerVerificationSchema.schema());
        });
    }

    @Test
    @DisplayName("perguntas não críticas e respostas determinísticas não são verificadas")
    void shouldSkipNonCriticalAndDeterministicAnswers() {
        responseRepository.save(AiAnalysisResponse.fromLlm(
                UUID.randomUUID(),
                legalCaseId,
                QuestionKey.COMPLIES_WITH_LAW_AND_POLICY,
                "Resposta a uma pergunta não crítica.",
                ConfidenceScore.of(0.7),
                List.of(chunkId),
                "claude-opus-5",
                UUID.randomUUID(),
                NOW));
        responseRepository.save(AiAnalysisResponse.deterministic(
                UUID.randomUUID(),
                legalCaseId,
                QuestionKey.HAS_SUFFICIENT_DOCUMENTATION,
                "Documentação incompleta: faltam os documentos obrigatórios FINANCIAL_OPINION.",
                NOW));

        assertThat(useCase.verify(legalCaseId)).isEqualTo(VerificationSummary.NONE);
        assertThat(llm.requests()).isEmpty();
    }

    @Test
    @DisplayName("uma resposta sem trecho citado não tem o que ser conferido")
    void shouldSkipAnswersWithoutCitations() {
        responseRepository.save(AiAnalysisResponse.fromLlm(
                UUID.randomUUID(),
                legalCaseId,
                QuestionKey.CAN_SIGN_CONTRACT,
                AiAnalysisResponse.NOT_FOUND_IN_KNOWLEDGE_BASE + " para esta pergunta.",
                ConfidenceScore.of(0.1),
                List.of(),
                "claude-opus-5",
                UUID.randomUUID(),
                NOW));

        assertThat(useCase.verify(legalCaseId)).isEqualTo(VerificationSummary.NONE);
        assertThat(llm.requests()).isEmpty();
    }

    @Test
    @DisplayName("uma verificação que não conclui deixa a resposta sem selo, sem impedir a entrega")
    void shouldLeaveTheAnswerUnverifiedWhenTheCheckItselfFails() {
        AiAnalysisResponse original = givenCriticalAnswer(List.of(chunkId));
        llm.byDefault(request -> {
            throw new LlmRefusalException("claude-opus-5", "legal_advice");
        });

        VerificationSummary summary = useCase.verify(legalCaseId);

        AiAnalysisResponse unchanged = reload();
        assertThat(summary).isEqualTo(new VerificationSummary(0, 0, 1, 1));
        assertThat(unchanged.verificationStatus()).isEqualTo(VerificationStatus.NOT_VERIFIED);
        assertThat(unchanged.confidenceScore()).isEqualTo(original.confidenceScore());
    }

    @Test
    @DisplayName("se os trechos citados sumiram da base, a fundamentação não pôde ser conferida")
    void shouldFailWhenCitedChunksNoLongerExist() {
        givenCriticalAnswer(List.of(UUID.randomUUID()));

        VerificationSummary summary = useCase.verify(legalCaseId);

        assertThat(summary).isEqualTo(new VerificationSummary(0, 1, 0, 0));
        assertThat(reload().verificationStatus()).isEqualTo(VerificationStatus.FAILED);
        assertThat(reload().verificationNotes()).contains("não estão mais na base normativa");
        assertThat(llm.requests()).isEmpty();
    }

    @Test
    @DisplayName("uma resposta já verificada não é verificada de novo")
    void shouldNotVerifyTwice() {
        givenCriticalAnswer(List.of(chunkId));
        llm.byDefault(request -> ScriptedLlmClient.response(AnalysisTestDoubles.verificationJson(true, "Sustentada.")));
        useCase.verify(legalCaseId);

        assertThat(useCase.verify(legalCaseId)).isEqualTo(VerificationSummary.NONE);
        assertThat(llm.requests()).hasSize(1);
    }

    @Test
    @DisplayName("a lista de perguntas críticas pode ser ampliada por configuração")
    void shouldAllowExtendingTheCriticalQuestions() {
        responseRepository.save(AiAnalysisResponse.fromLlm(
                UUID.randomUUID(),
                legalCaseId,
                QuestionKey.COMPLIES_WITH_LAW_AND_POLICY,
                "Resposta fundamentada.",
                ConfidenceScore.of(0.7),
                List.of(chunkId),
                "claude-opus-5",
                UUID.randomUUID(),
                NOW));
        llm.byDefault(request -> ScriptedLlmClient.response(AnalysisTestDoubles.verificationJson(true, "Sustentada.")));
        VerifyAiAnalysisResponseUseCase ampliado = newUseCase(Set.of(QuestionKey.COMPLIES_WITH_LAW_AND_POLICY));

        assertThat(ampliado.criticalQuestions()).containsExactly(QuestionKey.COMPLIES_WITH_LAW_AND_POLICY);
        assertThat(ampliado.verify(legalCaseId).verified()).isEqualTo(1);
    }

    @Test
    @DisplayName("sem versão ativa do prompt de verificação, a falha é de configuração e sobe")
    void shouldRejectMissingPromptVersion() {
        givenCriticalAnswer(List.of(chunkId));
        promptRepository = new InMemoryPromptVersionRepository();

        assertThatExceptionOfType(PromptVersionNotFoundException.class)
                .isThrownBy(() -> newUseCase(QuestionKey.criticalQuestions()).verify(legalCaseId));
    }

    private AiAnalysisResponse givenCriticalAnswer(List<UUID> citedChunks) {
        return responseRepository.save(AiAnalysisResponse.fromLlm(
                UUID.randomUUID(),
                legalCaseId,
                QuestionKey.CAN_SIGN_CONTRACT,
                ANSWER_TEXT,
                ConfidenceScore.of(0.82),
                citedChunks,
                "claude-opus-5",
                UUID.randomUUID(),
                NOW));
    }

    private AiAnalysisResponse reload() {
        return responseRepository
                .findByLegalCaseIdAndQuestionKey(legalCaseId, QuestionKey.CAN_SIGN_CONTRACT)
                .orElseThrow();
    }

    private UUID givenKnowledgeBase() {
        UUID sourceId = new IngestKnowledgeBaseSourceService(
                        sourceRepository,
                        chunkRepository,
                        new LexicalEmbeddingClient(),
                        new TextChunker(ChunkingPolicy.DEFAULT),
                        (format, content) -> {
                            throw new UnsupportedOperationException();
                        },
                        new InMemoryIdempotencyStore(),
                        KnowledgeBaseTestDoubles.directTransactionRunner(),
                        UUID::randomUUID)
                .ingest(new IngestKnowledgeBaseSourceCommand(
                        "Política de Alçadas", KnowledgeBaseSourceType.INTERNAL_POLICY, null, ALCADAS))
                .source()
                .id();
        return chunkRepository.findBySourceId(sourceId).stream()
                .map(KnowledgeBaseChunk::id)
                .findFirst()
                .orElseThrow();
    }
}
