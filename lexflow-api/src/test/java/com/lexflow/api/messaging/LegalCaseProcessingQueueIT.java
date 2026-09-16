package com.lexflow.api.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.lexflow.api.AbstractApiIT;
import com.lexflow.application.legalcase.LegalCaseReceivedEvent;
import com.lexflow.application.legalcase.LegalCaseReceivedEventPublisher;
import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseType;
import com.lexflow.infrastructure.messaging.LegalCaseReceivedEventListener;
import com.lexflow.infrastructure.messaging.RabbitMqConfiguration;
import com.lexflow.infrastructure.persistence.entity.ProcessingEventStatus;
import com.lexflow.infrastructure.persistence.repository.DocumentTextContentJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseStatusHistoryJpaRepository;
import com.lexflow.infrastructure.persistence.repository.ProcessingEventJpaRepository;
import com.lexflow.infrastructure.testsupport.DocumentFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
 * Testes de integração da orquestração assíncrona (Prompt 07), com RabbitMQ, PostgreSQL e MinIO
 * reais.
 *
 * <p>O foco aqui é a entrega — idempotência, retry e dead-letter. A classificação e a extração de
 * texto que o consumo dispara (Prompt 08) são verificadas em {@code LegalCaseClassificationExtractionIT}.
 *
 * <p>O consumidor começa parado e é iniciado dentro de cada teste, depois que o cenário está montado.
 * Sem isso, o consumo em segundo plano correria contra as asserções e o resultado dependeria de quem
 * chegasse primeiro ao banco.
 *
 * <p>Requer Docker em execução.
 */
class LegalCaseProcessingQueueIT extends AbstractApiIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private LegalCaseReceivedEventPublisher eventPublisher;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private LegalCaseJpaRepository legalCaseRepository;

    @Autowired
    private LegalCaseStatusHistoryJpaRepository statusHistoryRepository;

    @Autowired
    private ProcessingEventJpaRepository processingEventRepository;

    @Autowired
    private DocumentTextContentJpaRepository textContentRepository;

    @BeforeEach
    void drainQueues() {
        stopListener();
        rabbitAdmin.purgeQueue(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_DLQ, false);
    }

    @AfterEach
    void stopConsumer() {
        stopListener();
    }

    private void startListener() {
        listenerRegistry.getListenerContainer(LegalCaseReceivedEventListener.LISTENER_ID).start();
    }

    private void stopListener() {
        listenerRegistry.getListenerContainer(LegalCaseReceivedEventListener.LISTENER_ID).stop();
    }

    /** Cria uma demanda pela API, que publica o evento sem consumi-lo (o listener está parado). */
    private UUID ingestLegalCase() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("caseType", "CONTRACT_SIGNING");
        body.add("requester", "ana.silva");
        body.add("files", new ByteArrayResource(DocumentFixtures.read("contrato-texto-nativo.pdf")) {
            @Override
            public String getFilename() {
                return "contrato.pdf";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/legal-cases", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }

    /**
     * Cria a demanda e descarta o evento que a própria ingestão publicou.
     *
     * <p>Necessário nos testes que publicam um evento construído à mão: sem descartar o da ingestão,
     * a fila teria dois eventos distintos para a mesma demanda, cada um com a sua chave de
     * idempotência, e o que se estaria medindo seria uma corrida entre eventos diferentes — não a
     * duplicidade do mesmo evento, que é o que o critério de aceite pede.
     */
    private UUID ingestLegalCaseAndDiscardItsEvent() {
        UUID legalCaseId = ingestLegalCase();
        await().atMost(TIMEOUT)
                .until(() -> messageCountOf(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_QUEUE) == 1);
        rabbitAdmin.purgeQueue(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_QUEUE, false);
        return legalCaseId;
    }

    private static LegalCaseReceivedEvent eventFor(UUID legalCaseId) {
        return LegalCaseReceivedEvent.of(
                UUID.randomUUID(), legalCaseId, LegalCaseType.CONTRACT_SIGNING, CasePriority.NORMAL, 1, Instant.now());
    }

    private LegalCaseStatus statusOf(UUID legalCaseId) {
        return legalCaseRepository.findById(legalCaseId).orElseThrow().getStatus();
    }

    private int messageCountOf(String queue) {
        return rabbitAdmin.getQueueInfo(queue).getMessageCount();
    }

    @Test
    @DisplayName("a ingestão publica o evento e o consumo leva a demanda até EXTRACTING")
    void shouldProcessEventAndAdvanceStatus() {
        UUID legalCaseId = ingestLegalCase();
        assertThat(statusOf(legalCaseId)).isEqualTo(LegalCaseStatus.RECEIVED);

        startListener();

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(statusOf(legalCaseId)).isEqualTo(LegalCaseStatus.EXTRACTING);
            // Cada transição deixou rastro: registro inicial, CLASSIFYING e EXTRACTING. As duas
            // últimas nascem na mesma transação, então a ordem é conferida pelo status anterior.
            assertThat(statusHistoryRepository.findByLegalCaseIdOrderByChangedAtAsc(legalCaseId))
                    .hasSize(3)
                    .filteredOn(entry -> entry.getPreviousStatus() != null)
                    .allSatisfy(entry -> assertThat(entry.getChangedBy()).isEqualTo("SYSTEM"))
                    .extracting(entry -> entry.getPreviousStatus() + "->" + entry.getNewStatus())
                    .containsExactlyInAnyOrder("RECEIVED->CLASSIFYING", "CLASSIFYING->EXTRACTING");
            assertThat(processingEventRepository.findAll())
                    .filteredOn(event -> legalCaseId.equals(event.getAggregateId()))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getEventType()).isEqualTo(LegalCaseReceivedEvent.EVENT_TYPE);
                        assertThat(event.getStatus()).isEqualTo(ProcessingEventStatus.PROCESSED);
                    });
        });
    }

    @Test
    @DisplayName("o mesmo evento publicado duas vezes é processado uma única vez")
    void shouldProcessDuplicatedEventOnlyOnce() {
        UUID legalCaseId = ingestLegalCaseAndDiscardItsEvent();
        LegalCaseReceivedEvent event = eventFor(legalCaseId);

        eventPublisher.publish(event);
        eventPublisher.publish(event);
        startListener();

        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(messageCountOf(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_QUEUE))
                        .isZero());

        // A segunda entrega foi descartada pela chave de idempotência: um único processamento, sem
        // transições nem extrações repetidas.
        assertThat(statusOf(legalCaseId)).isEqualTo(LegalCaseStatus.EXTRACTING);
        assertThat(statusHistoryRepository.findByLegalCaseIdOrderByChangedAtAsc(legalCaseId))
                .hasSize(3);
        assertThat(textContentRepository.findByLegalCaseId(legalCaseId)).hasSize(1);
        assertThat(processingEventRepository.findByIdempotencyKey(event.idempotencyKey()))
                .get()
                .satisfies(processed -> {
                    assertThat(processed.getStatus()).isEqualTo(ProcessingEventStatus.PROCESSED);
                    assertThat(processed.getAggregateId()).isEqualTo(legalCaseId);
                    assertThat(processed.getProcessedAt()).isNotNull();
                });
        assertThat(messageCountOf(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_DLQ)).isZero();
    }

    @Test
    @DisplayName("evento que falha repetidamente vai para a dead-letter sem travar os demais")
    void shouldSendFailingEventToDeadLetterWithoutBlockingOthers() {
        // Demanda inexistente: o caso de uso lança, o retry se esgota e a mensagem é rejeitada.
        LegalCaseReceivedEvent failing = eventFor(UUID.randomUUID());
        UUID healthyCaseId = ingestLegalCase();

        eventPublisher.publish(failing);
        startListener();

        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(messageCountOf(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_DLQ))
                        .isEqualTo(1));

        // O evento falho ficou registrado como FAILED, e não como processado: se voltar, tenta de novo.
        assertThat(processingEventRepository.findByIdempotencyKey(failing.idempotencyKey()))
                .get()
                .satisfies(event -> {
                    assertThat(event.getStatus()).isEqualTo(ProcessingEventStatus.FAILED);
                    assertThat(event.getProcessedAt()).isNull();
                });

        // Critério de aceite: a falha não travou a fila — a demanda saudável seguiu sendo processada.
        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(statusOf(healthyCaseId)).isEqualTo(LegalCaseStatus.EXTRACTING));
        assertThat(messageCountOf(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_DLQ)).isEqualTo(1);
    }

    @Test
    @DisplayName("um evento já processado que volta à fila é descartado, sem erro")
    void shouldIgnoreRedeliveryOfProcessedEvent() {
        UUID legalCaseId = ingestLegalCaseAndDiscardItsEvent();
        LegalCaseReceivedEvent event = eventFor(legalCaseId);

        eventPublisher.publish(event);
        startListener();
        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(statusOf(legalCaseId)).isEqualTo(LegalCaseStatus.EXTRACTING));

        // Reentrega muito depois, com o evento já concluído.
        eventPublisher.publish(event);

        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(messageCountOf(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_QUEUE))
                        .isZero());
        assertThat(statusHistoryRepository.findByLegalCaseIdOrderByChangedAtAsc(legalCaseId))
                .hasSize(3);
        assertThat(messageCountOf(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_DLQ)).isZero();
    }
}
