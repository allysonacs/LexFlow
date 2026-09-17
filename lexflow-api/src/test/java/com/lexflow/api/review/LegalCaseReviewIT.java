package com.lexflow.api.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.lexflow.api.AbstractApiIT;
import com.lexflow.api.legalcase.LegalCaseController;
import com.lexflow.api.support.StubLlmClient;
import com.lexflow.domain.decision.DecisionType;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.infrastructure.messaging.LegalCaseReceivedEventListener;
import com.lexflow.infrastructure.messaging.RabbitMqConfiguration;
import com.lexflow.infrastructure.persistence.entity.KnowledgeBaseChunkEntity;
import com.lexflow.infrastructure.persistence.repository.DecisionJpaRepository;
import com.lexflow.infrastructure.persistence.repository.KnowledgeBaseChunkJpaRepository;
import com.lexflow.infrastructure.persistence.repository.KnowledgeBaseSourceJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseStatusHistoryJpaRepository;
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
 * API de revisão humana (Prompt 15), de ponta a ponta.
 *
 * <p>Inclui o critério de aceite: reenviar a mesma decisão com a mesma {@code Idempotency-Key} não
 * gera duas linhas em {@code decisions} nem duas transições de status.
 *
 * <p>Requer Docker em execução e o Tesseract instalado.
 */
class LegalCaseReviewIT extends AbstractApiIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final String BASE_PATH = LegalCaseController.BASE_PATH;

    private static final String ADMIN_USER = "admin";

    private static final String ADMIN_PASSWORD = "lexflow-admin";

    private static final String REVIEWER = "ana.silva";

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
    private LegalCaseJpaRepository legalCaseRepository;

    @Autowired
    private LegalCaseStatusHistoryJpaRepository statusHistoryRepository;

    @Autowired
    private DecisionJpaRepository decisionRepository;

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
    @DisplayName("a fila de revisão lista as demandas que aguardam decisão")
    void shouldListCasesAwaitingReview() {
        UUID legalCaseId = givenCaseAwaitingReview();

        ResponseEntity<String> response = restTemplate.getForEntity(
                BASE_PATH + "?status=PENDING_HUMAN_REVIEW&page=0&size=100", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains(legalCaseId.toString(), "\"status\":\"PENDING_HUMAN_REVIEW\"", "\"totalElements\"");
        // Um status desconhecido é recusado como qualquer outro valor inválido.
        assertThat(restTemplate.getForEntity(BASE_PATH + "?status=INEXISTENTE", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a análise traz cada resposta com a confiança, a verificação e o texto da fonte citada")
    void shouldExposeTheAnalysisWithCitedText() {
        UUID legalCaseId = givenCaseAwaitingReview();

        ResponseEntity<String> response =
                restTemplate.getForEntity(BASE_PATH + "/" + legalCaseId + "/analysis", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"awaitsDecision\":true")
                .contains("\"questionKey\":\"CAN_SIGN_CONTRACT\"")
                .contains("\"question\":\"Podemos assinar esse contrato?\"")
                .contains("\"confidenceScore\"")
                .contains("\"verificationStatus\":\"VERIFIED\"")
                // O texto da norma citada vem junto: sem ele o revisor só veria a conclusão da IA.
                .contains("diretor jurídico")
                .contains("\"sourceTitle\":\"IT Política de Alçadas\"");
    }

    @Test
    @DisplayName("critério de aceite: a mesma decisão com a mesma chave não gera duas linhas nem duas transições")
    void shouldRegisterTheSameDecisionOnlyOnce() {
        UUID legalCaseId = givenCaseAwaitingReview();
        HttpEntity<String> request = decision(DecisionType.APPROVED, "Alçada conferida.", "chave-decisao-1");

        ResponseEntity<String> first =
                restTemplate.postForEntity(BASE_PATH + "/" + legalCaseId + "/decisions", request, String.class);
        ResponseEntity<String> second =
                restTemplate.postForEntity(BASE_PATH + "/" + legalCaseId + "/decisions", request, String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(idOf(second)).isEqualTo(idOf(first));
        assertThat(decisionRepository.findByLegalCaseIdOrderByDecidedAtAsc(legalCaseId)).hasSize(1);
        assertThat(statusHistoryRepository.findByLegalCaseIdOrderByChangedAtAsc(legalCaseId))
                .filteredOn(entry -> entry.getNewStatus() == LegalCaseStatus.APPROVED)
                .hasSize(1);
        assertThat(statusOf(legalCaseId)).isEqualTo(LegalCaseStatus.APPROVED);
    }

    @Test
    @DisplayName("os três desfechos levam a demanda ao status correspondente")
    void shouldSupportTheThreeDecisionTypes() {
        UUID approved = givenCaseAwaitingReview();
        UUID rejected = givenCaseAwaitingReview();
        UUID returned = givenCaseAwaitingReview();

        restTemplate.postForEntity(
                BASE_PATH + "/" + approved + "/decisions", decision(DecisionType.APPROVED, null, null), String.class);
        restTemplate.postForEntity(
                BASE_PATH + "/" + rejected + "/decisions",
                decision(DecisionType.REJECTED, "Fora da alçada.", null),
                String.class);
        ResponseEntity<String> returnedResponse = restTemplate.postForEntity(
                BASE_PATH + "/" + returned + "/decisions",
                decision(DecisionType.RETURNED_FOR_CORRECTION, "Falta o parecer financeiro assinado.", null),
                String.class);

        assertThat(statusOf(approved)).isEqualTo(LegalCaseStatus.APPROVED);
        assertThat(statusOf(rejected)).isEqualTo(LegalCaseStatus.REJECTED);
        assertThat(statusOf(returned)).isEqualTo(LegalCaseStatus.RETURNED_FOR_CORRECTION);
        assertThat(returnedResponse.getBody())
                .contains("\"decisionType\":\"RETURNED_FOR_CORRECTION\"")
                .contains("\"decidedBy\":\"" + REVIEWER + "\"");
        // A decisão aparece na análise da demanda.
        assertThat(restTemplate.getForEntity(BASE_PATH + "/" + approved + "/analysis", String.class).getBody())
                .contains("\"awaitsDecision\":false", "\"decisionType\":\"APPROVED\"");
    }

    @Test
    @DisplayName("uma devolução sem comentários e uma decisão fora da revisão são recusadas")
    void shouldRejectInvalidDecisions() {
        UUID legalCaseId = givenCaseAwaitingReview();

        ResponseEntity<String> semComentario = restTemplate.postForEntity(
                BASE_PATH + "/" + legalCaseId + "/decisions",
                decision(DecisionType.RETURNED_FOR_CORRECTION, null, null),
                String.class);
        restTemplate.postForEntity(
                BASE_PATH + "/" + legalCaseId + "/decisions", decision(DecisionType.APPROVED, null, null), String.class);
        ResponseEntity<String> duasVezes = restTemplate.postForEntity(
                BASE_PATH + "/" + legalCaseId + "/decisions", decision(DecisionType.REJECTED, null, null), String.class);

        assertThat(semComentario.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Já decidida: a máquina de estados recusa uma segunda decisão.
        assertThat(duasVezes.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duasVezes.getBody()).contains("CONFLICT");
    }

    @Test
    @DisplayName("uma demanda devolvida volta a RECEIVED quando a documentação é reenviada")
    void shouldReopenCaseWhenDocumentationIsResubmitted() {
        UUID legalCaseId = givenCaseAwaitingReview();
        restTemplate.postForEntity(
                BASE_PATH + "/" + legalCaseId + "/decisions",
                decision(DecisionType.RETURNED_FOR_CORRECTION, "Falta o parecer financeiro assinado.", null),
                String.class);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("files", fixture("peticao-digitalizada.pdf"));
        body.add("documentTypes", "SIGNATORY_POWERS");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set(LegalCaseController.USER_ID_HEADER, "requisitante");

        ResponseEntity<String> response = restTemplate.exchange(
                BASE_PATH + "/" + legalCaseId + "/documents",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"RECEIVED\"");
        assertThat(statusOf(legalCaseId)).isEqualTo(LegalCaseStatus.RECEIVED);
        // As respostas anteriores foram descartadas: elas falavam de outra documentação.
        assertThat(restTemplate.getForEntity(BASE_PATH + "/" + legalCaseId + "/analysis", String.class).getBody())
                .contains("\"questions\":[]");
        assertThat(statusHistoryRepository.findByLegalCaseIdOrderByChangedAtAsc(legalCaseId))
                .anySatisfy(entry -> assertThat(entry.getReason()).contains("Documentação reenviada"));
    }

    /** Indexa a norma, ingere a demanda e espera o pipeline chegar à revisão humana. */
    private UUID givenCaseAwaitingReview() {
        if (createdSources.isEmpty()) {
            indexPolicy();
        }
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
        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(statusOf(legalCaseId)).isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW));
        stopListener();
        return legalCaseId;
    }

    private void indexPolicy() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth(ADMIN_USER, ADMIN_PASSWORD)
                .postForEntity(
                        "/api/v1/knowledge-base/sources",
                        json("""
                             {"title": "IT Política de Alçadas", "sourceType": "INTERNAL_POLICY", "text": "%s"}
                             """
                                .formatted(ALCADAS.replace("\n", "\\n"))),
                        String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        createdSources.add(idOf(response));
    }

    private static ByteArrayResource fixture(String name) {
        return new ByteArrayResource(DocumentFixtures.read(name)) {
            @Override
            public String getFilename() {
                return name;
            }
        };
    }

    private static HttpEntity<String> decision(DecisionType type, String comments, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(LegalCaseController.USER_ID_HEADER, REVIEWER);
        if (idempotencyKey != null) {
            headers.set(LegalCaseController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        String body = comments == null
                ? "{\"decisionType\": \"%s\"}".formatted(type)
                : "{\"decisionType\": \"%s\", \"comments\": \"%s\"}".formatted(type, comments);
        return new HttpEntity<>(body, headers);
    }

    private static HttpEntity<String> json(String body) {
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
