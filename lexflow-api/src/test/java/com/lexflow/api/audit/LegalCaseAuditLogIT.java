package com.lexflow.api.audit;

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
import com.lexflow.infrastructure.persistence.entity.KnowledgeBaseChunkEntity;
import com.lexflow.infrastructure.persistence.repository.KnowledgeBaseChunkJpaRepository;
import com.lexflow.infrastructure.persistence.repository.KnowledgeBaseSourceJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import com.lexflow.infrastructure.testsupport.DocumentFixtures;
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
 * Critério de aceite do Prompt 16: a linha do tempo devolvida por
 * {@code GET /api/v1/legal-cases/{id}/audit-log} reconstrói fielmente a história de um caso de ponta
 * a ponta — ingestão, IA e decisão.
 *
 * <p>Requer Docker em execução e o Tesseract instalado.
 */
class LegalCaseAuditLogIT extends AbstractApiIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final String BASE_PATH = LegalCaseController.BASE_PATH;

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

    private final List<UUID> createdSources = new ArrayList<>();

    @BeforeEach
    void startClean() {
        stopListener();
        llm.reset();
        rabbitAdmin.purgeQueue(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_DLQ, false);
        rabbitAdmin.purgeQueue(RabbitMqConfiguration.DECISION_REGISTERED_QUEUE, false);
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
    @DisplayName("a linha do tempo reconstrói a história do caso: ingestão, IA e decisão")
    void shouldReconstructTheWholeHistory() throws Exception {
        indexPolicy();
        UUID legalCaseId = ingestContract();
        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(statusOf(legalCaseId)).isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW));
        stopListener();
        registerApproval(legalCaseId);

        JsonNode trail = objectMapper.readTree(
                restTemplate.getForEntity(BASE_PATH + "/" + legalCaseId + "/audit-log", String.class).getBody());

        List<String> actions = new ArrayList<>();
        List<String> actors = new ArrayList<>();
        trail.forEach(entry -> {
            actions.add(entry.path("action").asText());
            actors.add(entry.path("actor").asText());
        });

        // Toda a história do caso, na ordem em que aconteceu.
        assertThat(actions)
                .startsWith("LEGAL_CASE_RECEIVED")
                .contains("STATUS_CHANGED", "AI_ANSWER_RECORDED", "AI_ANSWER_VERIFIED", "DECISION_REGISTERED");
        // A decisão e a transição que ela provoca acontecem na mesma transação e no mesmo instante,
        // então a ordem entre essas duas não é determinística — o que importa é que fecham a trilha.
        assertThat(actions.subList(actions.size() - 2, actions.size()))
                .containsExactlyInAnyOrder("DECISION_REGISTERED", "STATUS_CHANGED");
        // Quem fez cada coisa: o pipeline, o modelo e a pessoa.
        assertThat(actors).contains("SYSTEM", "AI", "ana.silva");
        // Uma entrada por transição de status depois da criação: CLASSIFYING, EXTRACTING,
        // AI_ANALYSIS_IN_PROGRESS, PENDING_HUMAN_REVIEW e APPROVED.
        assertThat(actions).filteredOn("STATUS_CHANGED"::equals).hasSize(5);
        trail.forEach(entry -> {
            assertThat(entry.path("entityType").asText()).isIn("LEGAL_CASE", "AI_ANALYSIS_RESPONSE", "DECISION");
            assertThat(entry.path("occurredAt").asText()).isNotBlank();
        });
    }

    @Test
    @DisplayName("o payload registra o que mudou, em metadados, sem conteúdo de documento")
    void shouldRecordMetadataOnly() {
        indexPolicy();
        UUID legalCaseId = ingestContract();
        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(statusOf(legalCaseId)).isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW));
        stopListener();

        String body = restTemplate
                .getForEntity(BASE_PATH + "/" + legalCaseId + "/audit-log", String.class)
                .getBody();

        assertThat(body)
                .contains("\"newStatus\":\"PENDING_HUMAN_REVIEW\"")
                .contains("\"questionKey\":\"CAN_SIGN_CONTRACT\"")
                .contains("\"answerSource\":\"LLM\"")
                // Nada do conteúdo dos documentos nem do texto das respostas entra na trilha.
                .doesNotContain("CONTRATO DE PRESTAÇÃO DE SERVIÇOS")
                .doesNotContain("Resposta fundamentada");
    }

    @Test
    @DisplayName("a trilha de uma demanda inexistente responde 404")
    void shouldAnswerNotFoundForUnknownCase() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(BASE_PATH + "/" + UUID.randomUUID() + "/audit-log", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("RESOURCE_NOT_FOUND");
    }

    private void registerApproval(UUID legalCaseId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(LegalCaseController.USER_ID_HEADER, "ana.silva");
        ResponseEntity<String> response = restTemplate.postForEntity(
                BASE_PATH + "/" + legalCaseId + "/decisions",
                new HttpEntity<>("{\"decisionType\": \"APPROVED\"}", headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void indexPolicy() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth("admin", "lexflow-admin")
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

    private UUID ingestContract() {
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
        listenerRegistry.getListenerContainer(LegalCaseReceivedEventListener.LISTENER_ID).start();
        return idOf(response);
    }

    private static ByteArrayResource fixture(String name) {
        return new ByteArrayResource(DocumentFixtures.read(name)) {
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

    private LegalCaseStatus statusOf(UUID legalCaseId) {
        return legalCaseRepository.findById(legalCaseId).orElseThrow().getStatus();
    }

    private static UUID idOf(ResponseEntity<String> response) {
        return UUID.fromString(response.getBody().replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }
}
