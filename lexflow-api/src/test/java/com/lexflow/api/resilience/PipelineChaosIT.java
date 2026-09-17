package com.lexflow.api.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.lexflow.api.AbstractApiIT;
import com.lexflow.api.legalcase.LegalCaseController;
import com.lexflow.api.support.StubLlmClient;
import com.lexflow.application.llm.LlmUnavailableException;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.infrastructure.messaging.LegalCaseReceivedEventListener;
import com.lexflow.infrastructure.messaging.RabbitMqConfiguration;
import com.lexflow.infrastructure.persistence.entity.ProcessingEventStatus;
import com.lexflow.infrastructure.persistence.repository.AiAnalysisResponseJpaRepository;
import com.lexflow.infrastructure.persistence.repository.AiExtractedFactJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseStatusHistoryJpaRepository;
import com.lexflow.infrastructure.persistence.repository.ProcessingEventJpaRepository;
import com.lexflow.infrastructure.testsupport.DocumentFixtures;
import java.time.Duration;
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
 * Testes de caos do pipeline (Prompt 17, item 3).
 *
 * <p>Inclui o critério de aceite: uma queda do provedor de LLM não perde nenhuma demanda em
 * processamento — ela retoma sozinha quando o serviço volta, e sem processar nada duas vezes.
 *
 * <p>A queda é simulada no dublê do LLM, e não desligando um container, porque é o efeito que importa:
 * a chamada externa falha com {@link LlmUnavailableException}, o consumidor deixa a exceção subir e a
 * mensagem volta para a fila. A janela de novas tentativas do consumidor é o que decide se a demanda
 * atravessa a queda ou vai para a dead-letter.
 *
 * <p>Requer Docker em execução e o Tesseract instalado.
 */
class PipelineChaosIT extends AbstractApiIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private static final String BASE_PATH = LegalCaseController.BASE_PATH;

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
    private AiExtractedFactJpaRepository factRepository;

    @Autowired
    private AiAnalysisResponseJpaRepository responseRepository;

    @Autowired
    private ProcessingEventJpaRepository processingEventRepository;

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

    @Test
    @DisplayName("critério de aceite: o LLM cai, volta, e a demanda retoma sem processar nada duas vezes")
    void shouldSurviveAnLlmOutageWithoutLosingOrDuplicatingWork() {
        // Três tentativas falham como se o provedor estivesse fora; a quarta encontra o serviço de pé.
        // Nos testes a janela de novas tentativas do consumidor é comprimida para menos de um segundo
        // (ver RabbitMqTestcontainersConfiguration); em produção ela passa de dois minutos, que é o
        // que faz uma queda de 30 segundos ser atravessada sem a demanda ir para a dead-letter.
        llm.enqueue(outage(), outage(), outage());

        UUID legalCaseId = ingestContract();

        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(statusOf(legalCaseId)).isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW));

        // Nada foi perdido e nada foi para a dead-letter.
        assertThat(messageCountOf(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_DLQ)).isZero();
        // Nada foi feito duas vezes: um registro de fatos por documento e uma resposta por pergunta.
        assertThat(factRepository.findByLegalCaseId(legalCaseId)).hasSize(1);
        assertThat(responseRepository.findByLegalCaseId(legalCaseId)).hasSize(3);
        // Uma transição por etapa, apesar das três entregas da mensagem.
        assertThat(statusHistoryRepository.findByLegalCaseIdOrderByChangedAtAsc(legalCaseId))
                .extracting(entry -> entry.getPreviousStatus() + "->" + entry.getNewStatus())
                .containsExactlyInAnyOrder(
                        "null->RECEIVED",
                        "RECEIVED->CLASSIFYING",
                        "CLASSIFYING->EXTRACTING",
                        "EXTRACTING->AI_ANALYSIS_IN_PROGRESS",
                        "AI_ANALYSIS_IN_PROGRESS->PENDING_HUMAN_REVIEW");
        // O evento terminou marcado como processado, e não como falho.
        assertThat(processingEventRepository.findAll())
                .filteredOn(event -> legalCaseId.equals(event.getAggregateId())
                        && event.getEventType().equals("LEGAL_CASE_RECEIVED"))
                .singleElement()
                .satisfies(event -> assertThat(event.getStatus()).isEqualTo(ProcessingEventStatus.PROCESSED));
    }

    @Test
    @DisplayName("com o consumidor fora do ar, as demandas esperam na fila e são processadas quando ele volta")
    void shouldResumeWhenTheConsumerComesBack() {
        // O consumidor sobe parado nos testes: aqui isso simula a indisponibilidade do lado que consome.
        UUID first = ingestWithoutStartingListener();
        UUID second = ingestWithoutStartingListener();

        assertThat(statusOf(first)).isEqualTo(LegalCaseStatus.RECEIVED);
        assertThat(statusOf(second)).isEqualTo(LegalCaseStatus.RECEIVED);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() ->
                        assertThat(messageCountOf(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_QUEUE)).isEqualTo(2));

        listenerRegistry.getListenerContainer(LegalCaseReceivedEventListener.LISTENER_ID).start();

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(statusOf(first)).isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW);
            assertThat(statusOf(second)).isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW);
        });
        assertThat(messageCountOf(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_DLQ)).isZero();
    }

    /** Uma chamada que falha como se o provedor estivesse fora do ar. */
    private static java.util.function.Function<com.lexflow.application.llm.LlmRequest, com.lexflow.application.llm.LlmResponse> outage() {
        return request -> {
            throw new LlmUnavailableException("Provedor de LLM indisponível (queda simulada)", 503);
        };
    }

    private UUID ingestContract() {
        UUID legalCaseId = ingestWithoutStartingListener();
        listenerRegistry.getListenerContainer(LegalCaseReceivedEventListener.LISTENER_ID).start();
        return legalCaseId;
    }

    private UUID ingestWithoutStartingListener() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("caseType", "CONTRACT_SIGNING");
        body.add("requester", "requisitante");
        body.add("files", new ByteArrayResource(DocumentFixtures.read("contrato-texto-nativo.pdf")) {
            @Override
            public String getFilename() {
                return "contrato.pdf";
            }
        });
        body.add("documentTypes", "CONTRACT_DRAFT");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response =
                restTemplate.exchange(BASE_PATH, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }

    private LegalCaseStatus statusOf(UUID legalCaseId) {
        return legalCaseRepository.findById(legalCaseId).orElseThrow().getStatus();
    }

    private int messageCountOf(String queue) {
        return rabbitAdmin.getQueueInfo(queue).getMessageCount();
    }
}
