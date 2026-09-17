package com.lexflow.api.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.api.AbstractApiIT;
import com.lexflow.api.support.StubLlmClient;
import com.lexflow.application.fact.ExtractLegalFactsUseCase;
import com.lexflow.application.fact.FactExtractionSchema;
import com.lexflow.application.llm.LlmRequest;
import com.lexflow.application.llm.LlmResponseValidationException;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseType;
import com.lexflow.infrastructure.messaging.LegalCaseReceivedEventListener;
import com.lexflow.infrastructure.messaging.RabbitMqConfiguration;
import com.lexflow.infrastructure.persistence.entity.AiExtractedFactEntity;
import com.lexflow.infrastructure.persistence.entity.LegalCaseAlertEntity;
import com.lexflow.infrastructure.persistence.repository.AiExtractedFactJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseAlertJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseStatusHistoryJpaRepository;
import com.lexflow.infrastructure.persistence.repository.PromptVersionJpaRepository;
import com.lexflow.infrastructure.testsupport.DocumentFixtures;
import java.time.Duration;
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
 * Critério de aceite do Prompt 11, de ponta a ponta, com o LLM simulado:
 *
 * <ul>
 *   <li>o contrato de teste entra pela API e os fatos gravados batem com o gabarito;
 *   <li>uma extração malformada nunca é gravada como se fosse válida — a demanda recebe alerta e para.
 * </ul>
 *
 * <p>Requer Docker em execução e o Tesseract instalado.
 */
class LegalCaseFactExtractionIT extends AbstractApiIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

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
    private LegalCaseStatusHistoryJpaRepository statusHistoryRepository;

    @Autowired
    private AiExtractedFactJpaRepository factRepository;

    @Autowired
    private LegalCaseAlertJpaRepository alertRepository;

    @Autowired
    private PromptVersionJpaRepository promptVersionRepository;

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
    }

    private void stopListener() {
        listenerRegistry.getListenerContainer(LegalCaseReceivedEventListener.LISTENER_ID).stop();
    }

    /** Envia o contrato de teste como demanda de assinatura de contrato e inicia o consumo. */
    private UUID ingestContract() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("caseType", "CONTRACT_SIGNING");
        body.add("requester", "ana.silva");
        body.add("files", new ByteArrayResource(DocumentFixtures.read("contrato-texto-nativo.pdf")) {
            @Override
            public String getFilename() {
                return "contrato.pdf";
            }
        });
        body.add("documentTypes", "CONTRACT_DRAFT");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/legal-cases", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID legalCaseId = UUID.fromString(response.getBody().replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
        listenerRegistry.getListenerContainer(LegalCaseReceivedEventListener.LISTENER_ID).start();
        return legalCaseId;
    }

    private LegalCaseStatus statusOf(UUID legalCaseId) {
        return legalCaseRepository.findById(legalCaseId).orElseThrow().getStatus();
    }

    private List<AiExtractedFactEntity> factsOf(UUID legalCaseId) {
        return factRepository.findByLegalCaseId(legalCaseId);
    }

    @Test
    @DisplayName("os fatos extraídos do contrato de teste batem com o gabarito, e a demanda segue para a análise")
    void shouldExtractFactsMatchingAnswerKey() throws Exception {
        UUID legalCaseId = ingestContract();

        // Com os fatos extraídos, o pipeline segue pela cadeia de prompts (Prompt 13) até a revisão
        // humana; o que este teste verifica é o que a extração de fatos gravou no caminho.
        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(statusOf(legalCaseId)).isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW));

        UUID promptVersionId = promptVersionRepository
                .findByPromptKeyAndActiveIsTrue(ExtractLegalFactsUseCase.PROMPT_KEY)
                .orElseThrow()
                .getId();
        assertThat(factsOf(legalCaseId)).singleElement().satisfies(fact -> {
            assertThat(objectMapper.readTree(fact.getExtractedJson()))
                    .isEqualTo(objectMapper.readTree(
                            DocumentFixtures.readFactsAnswerKey(DocumentFixtures.CONTRACT_FACTS_ANSWER_KEY)));
            assertThat(fact.getModelVersion()).isEqualTo(StubLlmClient.MODEL);
            assertThat(fact.getPromptVersionId()).isEqualTo(promptVersionId);
        });
        assertThat(alertRepository.findByLegalCaseIdOrderByCreatedAtAscIdAsc(legalCaseId)).isEmpty();
        assertThat(statusHistoryRepository.findByLegalCaseIdOrderByChangedAtAsc(legalCaseId))
                .anySatisfy(entry -> assertThat(entry.getReason()).contains("Fatos extraídos de 1 documento"));

        // O LLM recebeu o texto extraído do PDF, com o schema do tipo e as regras de extração. A
        // extração é sempre a primeira chamada do pipeline.
        LlmRequest request = llm.requests().getFirst();
        assertThat(request.prompt()).contains("CONTRATO DE PRESTAÇÃO DE SERVIÇOS").contains("<documento nome=\"contrato.pdf\">");
        assertThat(request.outputSchema()).isEqualTo(FactExtractionSchema.forType(LegalCaseType.CONTRACT_SIGNING));
        assertThat(request.systemPrompt()).contains("Não deduza").contains("Não emita opinião jurídica");
    }

    @Test
    @DisplayName("extração malformada duas vezes: nada é gravado, a demanda recebe alerta e fica em EXTRACTING")
    void shouldNeverPersistMalformedExtraction() {
        llm.enqueue(
                request -> StubLlmClient.response("{\"parties\": \"não é uma lista\"}"),
                request -> {
                    throw new LlmResponseValidationException(
                            "A resposta do LLM não é JSON válido", StubLlmClient.MODEL, List.of("JSON inválido"));
                });

        UUID legalCaseId = ingestContract();

        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(alertRepository.findByLegalCaseIdOrderByCreatedAtAscIdAsc(legalCaseId))
                        .hasSize(1));
        LegalCaseAlertEntity alert = alertRepository.findByLegalCaseIdOrderByCreatedAtAscIdAsc(legalCaseId).getFirst();
        assertThat(alert.getAlertType().name()).isEqualTo("FACT_EXTRACTION_INVALID_OUTPUT");
        assertThat(alert.getMessage()).contains("2 tentativas").contains("JSON inválido");
        assertThat(alert.getResolvedAt()).isNull();

        // A segunda chamada reforçou o formato, citando o problema da primeira.
        assertThat(llm.requests()).hasSize(2);
        assertThat(llm.requests().get(1).prompt()).contains("A resposta anterior não seguiu o formato exigido");

        assertThat(factsOf(legalCaseId)).isEmpty();
        assertThat(statusOf(legalCaseId)).isEqualTo(LegalCaseStatus.EXTRACTING);
        // Não é falha técnica: nada vai para a dead-letter; a demanda espera um humano.
        assertThat(rabbitAdmin.getQueueInfo(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_DLQ).getMessageCount()).isZero();

        // O alerta aparece na consulta da demanda.
        assertThat(restTemplate.getForEntity("/api/v1/legal-cases/" + legalCaseId, String.class).getBody())
                .contains("\"alerts\":[{")
                .contains("\"type\":\"FACT_EXTRACTION_INVALID_OUTPUT\"")
                .doesNotContain("Empresa Exemplo");
    }
}
