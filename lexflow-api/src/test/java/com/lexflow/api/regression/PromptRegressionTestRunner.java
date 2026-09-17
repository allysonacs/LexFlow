package com.lexflow.api.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.api.legalcase.LegalCaseController;
import com.lexflow.application.analysis.LegalAnalysisSchema;
import com.lexflow.application.verification.AnswerVerificationSchema;
import com.lexflow.domain.ai.AiAnalysisResponse;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.infrastructure.messaging.LegalCaseReceivedEventListener;
import com.lexflow.infrastructure.persistence.entity.AiAnalysisResponseEntity;
import com.lexflow.infrastructure.persistence.entity.AiExtractedFactEntity;
import com.lexflow.infrastructure.persistence.entity.DocumentEntity;
import com.lexflow.infrastructure.persistence.entity.KnowledgeBaseChunkEntity;
import com.lexflow.infrastructure.persistence.entity.LegalCaseAlertEntity;
import com.lexflow.infrastructure.persistence.mapper.AiAnalysisResponseMapper;
import com.lexflow.infrastructure.persistence.repository.AiAnalysisResponseJpaRepository;
import com.lexflow.infrastructure.persistence.repository.AiExtractedFactJpaRepository;
import com.lexflow.infrastructure.persistence.repository.DocumentJpaRepository;
import com.lexflow.infrastructure.persistence.repository.KnowledgeBaseChunkJpaRepository;
import com.lexflow.infrastructure.persistence.repository.KnowledgeBaseSourceJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseAlertJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import com.lexflow.infrastructure.persistence.repository.PromptVersionJpaRepository;
import com.lexflow.infrastructure.testsupport.DocumentFixtures;
import com.lexflow.infrastructure.testsupport.MinioTestcontainersConfiguration;
import com.lexflow.infrastructure.testsupport.PostgresTestcontainersConfiguration;
import com.lexflow.infrastructure.testsupport.RabbitMqTestcontainersConfiguration;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Dataset de regressão de prompts (Prompt 19).
 *
 * <p><strong>Este é o único teste que chama o provedor de verdade.</strong> Ele existe para responder
 * uma pergunta que nenhum teste com dublê responde: mudar o texto de um prompt, ou trocar de modelo,
 * piorou as respostas? Por isso ele não roda no {@code ./gradlew build} nem em CI: custa dinheiro e
 * não é determinístico.
 *
 * <pre>
 * export ANTHROPIC_API_KEY=...
 * export LEXFLOW_EMBEDDINGS_API_KEY=...
 * ./gradlew regressionTest
 * </pre>
 *
 * <p>O relatório sai em {@code lexflow-api/build/reports/prompt-regression}. Como interpretá-lo e como
 * acrescentar casos está em {@code docs/prompt-regression.md}.
 *
 * <p>Requer Docker em execução e o Tesseract instalado.
 */
@Tag("regression")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
    PostgresTestcontainersConfiguration.class,
    MinioTestcontainersConfiguration.class,
    RabbitMqTestcontainersConfiguration.class
})
class PromptRegressionTestRunner {

    /** Uma demanda inteira, com extração, análise e verificação, contra o provedor real. */
    private static final Duration CASE_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Piso de acerto abaixo do qual a execução é considerada uma regressão.
     *
     * <p>Não é 100%: o modelo não é determinístico, e exigir perfeição faria o dataset falhar por
     * ruído, o que ensinaria o time a ignorá-lo. O que se quer detectar é uma queda, e ela aparece
     * tanto neste piso quanto na comparação entre dois relatórios.
     */
    private static final double MINIMUM_CORRECT_RATE = 0.8;

    private static final String ADMIN_USER = "admin";

    private static final String ADMIN_PASSWORD = "lexflow-admin";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    private LegalCaseJpaRepository legalCaseRepository;

    @Autowired
    private DocumentJpaRepository documentRepository;

    @Autowired
    private AiExtractedFactJpaRepository factRepository;

    @Autowired
    private AiAnalysisResponseJpaRepository responseRepository;

    @Autowired
    private LegalCaseAlertJpaRepository alertRepository;

    @Autowired
    private KnowledgeBaseSourceJpaRepository sourceRepository;

    @Autowired
    private KnowledgeBaseChunkJpaRepository chunkRepository;

    @Autowired
    private PromptVersionJpaRepository promptVersionRepository;

    @Value("${lexflow.llm.default-model}")
    private String model;

    private final List<UUID> createdSources = new ArrayList<>();

    @AfterEach
    void removeIndexedSources() {
        for (UUID sourceId : createdSources) {
            List<KnowledgeBaseChunkEntity> chunks = chunkRepository.findBySourceIdOrderByChunkIndexAsc(sourceId);
            chunkRepository.deleteAll(chunks);
            sourceRepository.deleteById(sourceId);
        }
        createdSources.clear();
    }

    @Test
    @DisplayName("critério de aceite: os golden cases rodam contra o LLM real e produzem um relatório comparável")
    void shouldRunGoldenCasesAndReport() {
        requireApiKeys();
        GoldenCaseEvaluator evaluator = new GoldenCaseEvaluator(objectMapper);
        List<GoldenCase> goldenCases = GoldenCases.loadAll(objectMapper);
        assertThat(goldenCases).as("nenhum golden case cadastrado em src/test/resources/golden-cases").isNotEmpty();

        Instant executedAt = Instant.now();
        List<GoldenCaseResult> results = new ArrayList<>();
        for (GoldenCase goldenCase : goldenCases) {
            results.add(run(goldenCase, evaluator));
        }

        // O relatório é gravado antes de qualquer asserção: uma execução que reprova é exatamente a
        // que mais precisa ser lida.
        Path report = PromptRegressionReport.write(objectMapper, executedAt, model, promptVersions(), results);
        System.out.println("Relatório de regressão: " + report.toAbsolutePath());

        Map<String, Object> summary = PromptRegressionReport.summary(results);
        assertThat(results).allSatisfy(result ->
                assertThat(result.status()).as("caso %s", result.caseId()).isEqualTo("COMPLETED"));
        assertThat((Double) summary.get("correctQuestionRate"))
                .as("percentual de perguntas corretas (veja %s)", report)
                .isGreaterThanOrEqualTo(MINIMUM_CORRECT_RATE);
    }

    /** Executa um caso de ponta a ponta e o avalia. */
    private GoldenCaseResult run(GoldenCase goldenCase, GoldenCaseEvaluator evaluator) {
        long startedAt = System.currentTimeMillis();
        List<String> findings = new ArrayList<>();
        try {
            goldenCase.knowledgeSources().forEach(source -> indexSource(goldenCase, source));
            UUID legalCaseId = ingest(goldenCase);

            listenerRegistry.getListenerContainer(LegalCaseReceivedEventListener.LISTENER_ID).start();
            try {
                await().atMost(CASE_TIMEOUT)
                        .pollInterval(Duration.ofSeconds(2))
                        .until(() -> statusOf(legalCaseId) == LegalCaseStatus.PENDING_HUMAN_REVIEW
                                || !alertRepository.findByLegalCaseIdOrderByCreatedAtAscIdAsc(legalCaseId).isEmpty());
            } finally {
                listenerRegistry.getListenerContainer(LegalCaseReceivedEventListener.LISTENER_ID).stop();
            }

            List<LegalCaseAlertEntity> alerts = alertRepository.findByLegalCaseIdOrderByCreatedAtAscIdAsc(legalCaseId);
            alerts.forEach(alert ->
                    findings.add("alerta %s: %s".formatted(alert.getAlertType(), alert.getMessage())));

            Map<String, AiAnalysisResponse> answers = new LinkedHashMap<>();
            for (AiAnalysisResponseEntity entity : responseRepository.findByLegalCaseId(legalCaseId)) {
                answers.put(entity.getQuestionKey().name(), AiAnalysisResponseMapper.toDomain(entity));
            }

            List<GoldenCaseResult.QuestionResult> questions =
                    evaluator.evaluateAnswers(goldenCase, answers, this::citedSourceTitles);
            int[] facts = compareFacts(goldenCase, legalCaseId, evaluator, findings);

            LegalCaseStatus status = statusOf(legalCaseId);
            return new GoldenCaseResult(
                    goldenCase.id(),
                    goldenCase.caseType(),
                    status == LegalCaseStatus.PENDING_HUMAN_REVIEW ? "COMPLETED" : "BLOCKED",
                    System.currentTimeMillis() - startedAt,
                    goldenCase.expectedAnswers().size(),
                    (int) questions.stream().filter(GoldenCaseResult.QuestionResult::correct).count(),
                    (int) answers.values().stream()
                            .filter(answer -> answer.verificationStatus().name().equals("FAILED"))
                            .count(),
                    facts[0],
                    facts[1],
                    List.copyOf(findings),
                    questions);
        } catch (RuntimeException e) {
            findings.add("execução falhou: " + e.getMessage());
            return new GoldenCaseResult(
                    goldenCase.id(),
                    goldenCase.caseType(),
                    "ERROR",
                    System.currentTimeMillis() - startedAt,
                    goldenCase.expectedAnswers().size(),
                    0,
                    0,
                    0,
                    0,
                    List.copyOf(findings),
                    List.of());
        }
    }

    /** Confere o gabarito de fatos de cada documento; devolve {conferidos, acertos}. */
    private int[] compareFacts(
            GoldenCase goldenCase, UUID legalCaseId, GoldenCaseEvaluator evaluator, List<String> findings) {
        Map<UUID, String> fileNames = new LinkedHashMap<>();
        for (DocumentEntity document : documentRepository.findByLegalCaseId(legalCaseId)) {
            fileNames.put(document.getId(), document.getFileName());
        }
        Map<String, String> factsByFileName = new LinkedHashMap<>();
        for (AiExtractedFactEntity fact : factRepository.findByLegalCaseId(legalCaseId)) {
            factsByFileName.put(fileNames.get(fact.getDocumentId()), fact.getExtractedJson());
        }

        int checked = 0;
        int matched = 0;
        for (GoldenCase.GoldenDocument document : goldenCase.documents()) {
            if (document.expectedFacts() == null || document.expectedFacts().isEmpty()) {
                continue;
            }
            String extracted = factsByFileName.get(document.fixture());
            if (extracted == null) {
                findings.add("documento %s não teve fatos extraídos".formatted(document.fixture()));
                continue;
            }
            GoldenCaseEvaluator.FactComparison comparison =
                    evaluator.compareFacts(document.expectedFacts(), extracted);
            checked += comparison.checked();
            matched += comparison.matched();
            comparison.differences().forEach(difference ->
                    findings.add("fatos de %s — %s".formatted(document.fixture(), difference)));
        }
        return new int[] {checked, matched};
    }

    private List<String> citedSourceTitles(List<UUID> chunkIds) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        return chunkRepository.findCited(chunkIds).stream()
                .map(row -> row.getSourceTitle())
                .distinct()
                .toList();
    }

    private void indexSource(GoldenCase goldenCase, GoldenCase.KnowledgeSource source) {
        String text = GoldenCases.readText(goldenCase.id(), source.file());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of(
                    "title", source.title(),
                    "sourceType", source.sourceType(),
                    "text", text));
        } catch (Exception e) {
            throw new IllegalStateException("não foi possível montar a fonte normativa", e);
        }
        ResponseEntity<String> response = restTemplate
                .withBasicAuth(ADMIN_USER, ADMIN_PASSWORD)
                .postForEntity("/api/v1/knowledge-base/sources", new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        createdSources.add(idOf(response));
    }

    private UUID ingest(GoldenCase goldenCase) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("caseType", goldenCase.caseType());
        body.add("requester", goldenCase.requester());
        body.add("description", goldenCase.description());
        for (GoldenCase.GoldenDocument document : goldenCase.documents()) {
            body.add("files", new ByteArrayResource(DocumentFixtures.read(document.fixture())) {
                @Override
                public String getFilename() {
                    return document.fixture();
                }
            });
            body.add("documentTypes", document.documentType());
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.exchange(
                LegalCaseController.BASE_PATH, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return idOf(response);
    }

    /** Versões de prompt ativas: sem elas, dois relatórios não diriam por que diferem. */
    private Map<String, Integer> promptVersions() {
        Map<String, Integer> versions = new LinkedHashMap<>();
        List.of(
                        "FACT_EXTRACTION",
                        LegalAnalysisSchema.PROMPT_KEY,
                        AnswerVerificationSchema.PROMPT_KEY)
                .forEach(key -> promptVersionRepository
                        .findByPromptKeyAndActiveIsTrue(key)
                        .ifPresent(prompt -> versions.put(key, prompt.getVersion())));
        return versions;
    }

    /** Sem as chaves, o dataset não tem o que medir — e falhar aqui é mais claro do que falhar adiante. */
    private static void requireApiKeys() {
        if (isBlank(System.getenv("ANTHROPIC_API_KEY"))) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY não definida: o dataset de regressão chama o provedor de verdade");
        }
        if (isBlank(System.getenv("LEXFLOW_EMBEDDINGS_API_KEY")) && isBlank(System.getenv("VOYAGE_API_KEY"))) {
            throw new IllegalStateException(
                    "LEXFLOW_EMBEDDINGS_API_KEY (ou VOYAGE_API_KEY) não definida: sem embeddings não há recuperação normativa");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private LegalCaseStatus statusOf(UUID legalCaseId) {
        return legalCaseRepository.findById(legalCaseId).orElseThrow().getStatus();
    }

    private static UUID idOf(ResponseEntity<String> response) {
        return UUID.fromString(response.getBody().replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }
}
