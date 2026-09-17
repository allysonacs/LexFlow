package com.lexflow.api.legalcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.lexflow.api.AbstractApiIT;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.infrastructure.messaging.LegalCaseReceivedEventListener;
import com.lexflow.infrastructure.messaging.RabbitMqConfiguration;
import com.lexflow.infrastructure.persistence.repository.DocumentTextContentJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import com.lexflow.infrastructure.testsupport.DocumentFixtures;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * Checklist documental de ponta a ponta (Prompt 09): a demanda entra pela API com o tipo de cada
 * documento, o consumo gera e avalia o checklist, e o {@code GET .../checklist} responde
 * {@code HAS_SUFFICIENT_DOCUMENTATION} — tudo com as regras do seed e sem nenhuma IA.
 *
 * <p>Requer Docker em execução e o Tesseract instalado.
 */
class LegalCaseChecklistIT extends AbstractApiIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final String BASE_PATH = "/api/v1/legal-cases";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private LegalCaseJpaRepository legalCaseRepository;

    @Autowired
    private DocumentTextContentJpaRepository textContentRepository;

    @BeforeEach
    void startWithEmptyQueue() {
        stopListener();
        rabbitAdmin.purgeQueue(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_DLQ, false);
    }

    @AfterEach
    void stopConsumer() {
        stopListener();
    }

    private void stopListener() {
        listenerRegistry.getListenerContainer(LegalCaseReceivedEventListener.LISTENER_ID).stop();
    }

    /**
     * Envia uma demanda de assinatura de contrato.
     *
     * @param files nome do arquivo → tipo de documento (nulo para "sem tipo")
     */
    private ResponseEntity<String> post(Map<String, String> files) {
        Map<String, byte[]> contents = Map.of(
                "minuta.pdf", DocumentFixtures.read("contrato-texto-nativo.pdf"),
                "parecer.docx", DocumentFixtures.read("minuta-aditivo.docx"),
                "procuracao.png", DocumentFixtures.read("termo-de-acordo.png"));
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("caseType", "CONTRACT_SIGNING");
        body.add("requester", "ana.silva");
        files.forEach((name, documentType) -> {
            body.add("files", new ByteArrayResource(contents.get(name)) {
                @Override
                public String getFilename() {
                    return name;
                }
            });
            body.add("documentTypes", documentType == null ? "" : documentType);
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange(BASE_PATH, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static UUID idOf(ResponseEntity<String> response) {
        return UUID.fromString(response.getBody().replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }

    private UUID ingestAndProcess(Map<String, String> files) {
        ResponseEntity<String> response = post(files);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID legalCaseId = idOf(response);
        listenerRegistry.getListenerContainer(LegalCaseReceivedEventListener.LISTENER_ID).start();
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(legalCaseRepository.findById(legalCaseId).orElseThrow().getStatus())
                    .isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW);
            assertThat(textContentRepository.findByLegalCaseId(legalCaseId)).hasSize(files.size());
        });
        return legalCaseId;
    }

    private String checklistOf(UUID legalCaseId) {
        ResponseEntity<String> response =
                restTemplate.getForEntity(BASE_PATH + "/" + legalCaseId + "/checklist", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static Map<String, String> files(String... nameAndType) {
        Map<String, String> files = new LinkedHashMap<>();
        for (int index = 0; index < nameAndType.length; index += 2) {
            files.put(nameAndType[index], nameAndType[index + 1]);
        }
        return files;
    }

    @Test
    @DisplayName("com todos os documentos obrigatórios, a documentação é suficiente")
    void shouldAnswerSufficientWhenMandatoryDocumentsArePresent() {
        UUID legalCaseId = ingestAndProcess(files(
                "minuta.pdf", "contract_draft",
                "parecer.docx", "FINANCIAL_OPINION"));

        String checklist = checklistOf(legalCaseId);

        assertThat(checklist)
                .contains("\"evaluated\":true")
                .contains("\"hasSufficientDocumentation\":true")
                .contains("\"missingMandatoryDocumentTypes\":[]")
                .contains("\"requiredDocumentType\":\"CONTRACT_DRAFT\"")
                .contains("\"requiredDocumentType\":\"FINANCIAL_OPINION\"")
                .contains("\"requiredDocumentType\":\"SIGNATORY_POWERS\"");
        assertThat(checklist.split("\"status\":\"SATISFIED\"", -1)).hasSize(3);
        assertThat(checklist.split("\"status\":\"MISSING\"", -1)).hasSize(2);

        // O tipo informado no upload aparece normalizado na consulta da demanda.
        assertThat(restTemplate.getForEntity(BASE_PATH + "/" + legalCaseId, String.class).getBody())
                .contains("\"documentType\":\"CONTRACT_DRAFT\"");
    }

    @Test
    @DisplayName("faltando um obrigatório, a documentação nunca é suficiente, mesmo com o opcional presente")
    void shouldAnswerInsufficientWhenMandatoryDocumentIsMissing() {
        UUID legalCaseId = ingestAndProcess(files(
                "minuta.pdf", "CONTRACT_DRAFT",
                "procuracao.png", "SIGNATORY_POWERS",
                "parecer.docx", null));

        assertThat(checklistOf(legalCaseId))
                .contains("\"hasSufficientDocumentation\":false")
                .contains("\"missingMandatoryDocumentTypes\":[\"FINANCIAL_OPINION\"]");
    }

    @Test
    @DisplayName("antes do processamento, o checklist não foi avaliado e a resposta é negativa")
    void shouldReportNotEvaluatedBeforeProcessing() {
        UUID legalCaseId = idOf(post(files("minuta.pdf", "CONTRACT_DRAFT", "parecer.docx", "FINANCIAL_OPINION")));

        assertThat(checklistOf(legalCaseId))
                .contains("\"status\":\"RECEIVED\"")
                .contains("\"evaluated\":false")
                .contains("\"hasSufficientDocumentation\":false")
                .contains("\"items\":[]");
    }

    @Test
    @DisplayName("tipos de documento inválidos ou em quantidade diferente dos arquivos resultam em 400")
    void shouldRejectInvalidDocumentTypes() {
        long before = legalCaseRepository.count();

        MultiValueMap<String, Object> mismatched = new LinkedMultiValueMap<>();
        mismatched.add("caseType", "CONTRACT_SIGNING");
        mismatched.add("requester", "ana.silva");
        mismatched.add("files", new ByteArrayResource(DocumentFixtures.read("contrato-texto-nativo.pdf")) {
            @Override
            public String getFilename() {
                return "minuta.pdf";
            }
        });
        mismatched.add("documentTypes", "CONTRACT_DRAFT");
        mismatched.add("documentTypes", "FINANCIAL_OPINION");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> mismatch = restTemplate.exchange(
                BASE_PATH, HttpMethod.POST, new HttpEntity<>(mismatched, headers), String.class);
        ResponseEntity<String> invalid = post(files("minuta.pdf", "minuta do contrato"));

        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mismatch.getBody()).contains("documentTypes");
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody()).contains("Tipo de documento inválido");
        assertThat(legalCaseRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("checklist de demanda inexistente responde 404")
    void shouldReturnNotFoundForUnknownCase() {
        assertThat(restTemplate.getForEntity(BASE_PATH + "/" + UUID.randomUUID() + "/checklist", String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
