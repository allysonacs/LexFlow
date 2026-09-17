package com.lexflow.api.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.api.AbstractApiIT;
import com.lexflow.api.legalcase.LegalCaseController;
import com.lexflow.api.support.StubLlmClient;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.infrastructure.messaging.LegalCaseReceivedEventListener;
import com.lexflow.infrastructure.messaging.RabbitMqConfiguration;
import com.lexflow.infrastructure.observability.PipelineMetrics;
import com.lexflow.infrastructure.persistence.entity.KnowledgeBaseChunkEntity;
import com.lexflow.infrastructure.persistence.repository.KnowledgeBaseChunkJpaRepository;
import com.lexflow.infrastructure.persistence.repository.KnowledgeBaseSourceJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
 * Métricas de operação e de negócio (Prompt 18).
 *
 * <p>Inclui o critério de aceite: a concordância entre a IA e o revisor humano é calculável para um
 * conjunto de casos.
 *
 * <p>Requer Docker em execução e o Tesseract instalado.
 */
class MetricsIT extends AbstractApiIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final String BASE_PATH = LegalCaseController.BASE_PATH;

    private static final String ADMIN_USER = "admin";

    private static final String ADMIN_PASSWORD = "lexflow-admin";

    private static final String ALCADAS =
            """
            Art. 1º A assinatura de contratos de prestação de serviços com valor superior a cem mil reais depende de aprovação prévia do diretor jurídico.

            Art. 2º O contrato de prestação de serviços deve conter objeto, prazo, valor e cláusula de rescisão, e a documentação exigida é a minuta contratual e o parecer financeiro.

            Art. 3º Toda demanda deve estar de acordo com a legislação aplicável e com a política interna da empresa.

            Art. 4º A documentação de uma demanda de assinatura de contrato é suficiente quando a minuta contratual e o parecer financeiro estiverem anexados e legíveis.
            """;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private StubLlmClient llm;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LegalCaseJpaRepository legalCaseRepository;

    @Autowired
    private KnowledgeBaseSourceJpaRepository sourceRepository;

    @Autowired
    private KnowledgeBaseChunkJpaRepository chunkRepository;

    @Autowired
    private PipelineMetrics pipelineMetrics;

    private final List<UUID> createdSources = new ArrayList<>();

    @BeforeEach
    void startClean() {
        stopListener();
        llm.reset();
        rabbitAdmin.purgeQueue(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_DLQ, false);
    }

    @AfterEach
    void cleanUp() {
        stopListener();
        llm.reset();
        for (UUID sourceId : createdSources) {
            List<KnowledgeBaseChunkEntity> chunks = chunkRepository.findBySourceIdOrderByChunkIndexAsc(sourceId);
            chunkRepository.deleteAll(chunks);
            sourceRepository.deleteById(sourceId);
        }
        createdSources.clear();
    }

    private void stopListener() {
        listenerRegistry.getListenerContainer(LegalCaseReceivedEventListener.LISTENER_ID).stop();
    }

    @Test
    @DisplayName("critério de aceite: a concordância entre a IA e o revisor é calculável para casos decididos")
    void shouldComputeAiHumanAgreement() throws Exception {
        indexPolicy();
        UUID aprovada = givenCaseAwaitingReview();
        decide(aprovada, "APPROVED", null);

        JsonNode agreement = objectMapper.readTree(admin()
                .getForEntity(MetricsController.BASE_PATH + "/ai-human-agreement", String.class)
                .getBody());

        assertThat(agreement.path("decidedCases").asLong()).isPositive();
        assertThat(agreement.path("conclusiveCases").asLong()).isPositive();
        // Com a IA respondendo bem e o revisor aprovando, a leitura é de concordância.
        assertThat(agreement.path("agreements").asLong()).isPositive();
        assertThat(agreement.path("agreementRate").isNull()).isFalse();
        assertThat(agreement.path("byCaseType").has("CONTRACT_SIGNING")).isTrue();
    }

    @Test
    @DisplayName("aprovar uma demanda que a IA não sustentou conta como discordância")
    void shouldCountDisagreementWhenTheHumanApprovesAnUnsupportedAnalysis() throws Exception {
        indexPolicy();
        // A segunda checagem reprova a resposta crítica: a leitura da IA passa a ser desfavorável.
        llm.verificationSupported(false);
        UUID divergente = givenCaseAwaitingReview();
        decide(divergente, "APPROVED", null);

        JsonNode agreement = objectMapper.readTree(admin()
                .getForEntity(MetricsController.BASE_PATH + "/ai-human-agreement", String.class)
                .getBody());

        assertThat(agreement.path("disagreements").asLong()).isPositive();
        assertThat(agreement.path("disagreementSamples")).anySatisfy(sample -> {
            assertThat(sample.path("aiSuggestion").asText()).isEqualTo("UNFAVORABLE");
            assertThat(sample.path("decisionType").asText()).isEqualTo("APPROVED");
        });
    }

    @Test
    @DisplayName("o painel devolve fila por status, tempo por tipo, alertas e concordância")
    void shouldExposeTheDashboard() throws Exception {
        indexPolicy();
        UUID legalCaseId = givenCaseAwaitingReview();

        JsonNode dashboard = objectMapper.readTree(
                admin().getForEntity(MetricsController.BASE_PATH + "/dashboard", String.class).getBody());

        assertThat(dashboard.path("generatedAt").asText()).isNotBlank();
        assertThat(dashboard.path("casesByStatus").path("PENDING_HUMAN_REVIEW").asLong()).isPositive();
        assertThat(dashboard.path("averageTimeToReviewSeconds").path("CONTRACT_SIGNING").asDouble())
                .isPositive();
        assertThat(dashboard.has("openAlertsByType")).isTrue();
        assertThat(dashboard.has("agreement")).isTrue();
        assertThat(legalCaseRepository.findById(legalCaseId).orElseThrow().getStatus())
                .isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW);
    }

    @Test
    @DisplayName("as métricas técnicas saem no formato Prometheus, sem conteúdo de documento")
    void shouldExposeTechnicalMetrics() {
        indexPolicy();
        givenCaseAwaitingReview();
        // O tamanho das filas é lido do broker por uma coleta periódica; aqui ela é disparada na hora.
        pipelineMetrics.refreshQueueDepth();

        String metrics = restTemplate.getForEntity("/actuator/prometheus", String.class).getBody();

        assertThat(metrics)
                // Latência e desfecho de cada integração externa. Nos testes, o LLM e os embeddings
                // são dublês e não passam pelo cliente instrumentado; o storage é real.
                .contains("lexflow_external_call_seconds")
                .contains("integration=\"storage\"")
                // Tempo da criação até a revisão humana, por tipo de demanda.
                .contains("lexflow_legal_case_time_to_review_seconds")
                .contains("case_type=\"CONTRACT_SIGNING\"")
                // Tamanho das filas.
                .contains("lexflow_queue_depth")
                .contains(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_QUEUE)
                // Transições do pipeline.
                .contains("lexflow_legal_case_status_transitions_total")
                // Nenhuma etiqueta carrega conteúdo de documento nem identificador de demanda.
                .doesNotContain("CONTRATO DE PRESTAÇÃO DE SERVIÇOS");
    }

    @Test
    @DisplayName("as métricas de negócio são restritas ao papel ADMIN")
    void shouldRequireAdminRole() {
        assertThat(restTemplate
                        .getForEntity(MetricsController.BASE_PATH + "/dashboard", String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate
                        .getForEntity(MetricsController.BASE_PATH + "/ai-human-agreement", String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private TestRestTemplate admin() {
        return restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASSWORD);
    }

    private void decide(UUID legalCaseId, String decisionType, String comments) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(LegalCaseController.USER_ID_HEADER, "ana.silva");
        String body = comments == null
                ? "{\"decisionType\": \"%s\"}".formatted(decisionType)
                : "{\"decisionType\": \"%s\", \"comments\": \"%s\"}".formatted(decisionType, comments);
        ResponseEntity<String> response = restTemplate.postForEntity(
                BASE_PATH + "/" + legalCaseId + "/decisions", new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private UUID givenCaseAwaitingReview() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("caseType", "CONTRACT_SIGNING");
        body.add("requester", "requisitante");
        body.add("files", fixture("contrato-texto-nativo.pdf"));
        body.add("documentTypes", "CONTRACT_DRAFT");
        body.add("files", fixture("minuta-aditivo.docx"));
        body.add("documentTypes", "FINANCIAL_OPINION");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response =
                restTemplate.exchange(BASE_PATH, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID legalCaseId = idOf(response);

        listenerRegistry.getListenerContainer(LegalCaseReceivedEventListener.LISTENER_ID).start();
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(
                        legalCaseRepository.findById(legalCaseId).orElseThrow().getStatus())
                .isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW));
        stopListener();
        return legalCaseId;
    }

    private void indexPolicy() {
        ResponseEntity<String> response = admin()
                .postForEntity(
                        "/api/v1/knowledge-base/sources",
                        jsonEntity("""
                                {"title": "IT Política de Alçadas", "sourceType": "INTERNAL_POLICY", "text": "%s"}
                                """
                                .formatted(ALCADAS.replace("\n", "\\n"))),
                        String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        createdSources.add(idOf(response));
    }

    private static ByteArrayResource fixture(String name) {
        return new ByteArrayResource(com.lexflow.infrastructure.testsupport.DocumentFixtures.read(name)) {
            @Override
            public String getFilename() {
                return name;
            }
        };
    }

    private static HttpEntity<String> jsonEntity(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static UUID idOf(ResponseEntity<String> response) {
        return UUID.fromString(response.getBody().replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }
}
