package com.lexflow.application.legalcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.application.legalcase.support.InMemoryProcessingEventStore;
import com.lexflow.application.legalcase.support.InMemoryProcessingEventStore.State;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.DirectTransactionRunner;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryLegalCaseRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryStatusHistoryRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.SequentialIdGenerator;
import com.lexflow.domain.exception.InvalidStatusTransitionException;
import com.lexflow.domain.exception.LegalCaseNotFoundException;
import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseStatusTransitionRules;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cobre o consumo do evento sem broker nenhum.
 *
 * <p>É o contraponto rápido ao teste de integração com RabbitMQ: aqui se verifica a decisão —
 * reservar, avançar, descartar ou falhar —, lá se verifica a entrega.
 */
class ProcessLegalCaseReceivedEventServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    private InMemoryLegalCaseRepository legalCaseRepository;
    private InMemoryStatusHistoryRepository statusHistoryRepository;
    private InMemoryProcessingEventStore processingEventStore;
    private DirectTransactionRunner transactionRunner;
    private ProcessLegalCaseReceivedEventService service;

    @BeforeEach
    void setUp() {
        legalCaseRepository = new InMemoryLegalCaseRepository();
        statusHistoryRepository = new InMemoryStatusHistoryRepository();
        processingEventStore = new InMemoryProcessingEventStore();
        transactionRunner = new DirectTransactionRunner();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ProcessLegalCaseReceivedEventService(
                legalCaseRepository,
                statusHistoryRepository,
                new LegalCaseStatusTransitionService(
                        new LegalCaseStatusTransitionRules(), clock, new SequentialIdGenerator()),
                processingEventStore,
                transactionRunner);
    }

    private LegalCase givenLegalCase(LegalCaseStatus status) {
        LegalCase legalCase = LegalCase.receive(
                UUID.randomUUID(), "REF-1", LegalCaseType.CONTRACT_SIGNING, "ana.silva", CasePriority.NORMAL, NOW);
        if (status != LegalCaseStatus.RECEIVED) {
            legalCase = legalCase.transitionTo(status, NOW);
        }
        return legalCaseRepository.save(legalCase);
    }

    private static LegalCaseReceivedEvent eventFor(UUID legalCaseId) {
        return LegalCaseReceivedEvent.of(
                UUID.randomUUID(), legalCaseId, LegalCaseType.CONTRACT_SIGNING, CasePriority.NORMAL, 1, NOW);
    }

    @Test
    @DisplayName("a demanda avança para CLASSIFYING e o evento é marcado como processado")
    void shouldAdvanceToClassifying() {
        LegalCase legalCase = givenLegalCase(LegalCaseStatus.RECEIVED);
        LegalCaseReceivedEvent event = eventFor(legalCase.id());

        LegalCaseProcessingOutcome outcome = service.process(event);

        assertThat(outcome).isEqualTo(LegalCaseProcessingOutcome.PROCESSED);
        assertThat(outcome.isSkipped()).isFalse();
        assertThat(legalCaseRepository.findById(legalCase.id()))
                .get()
                .satisfies(updated -> assertThat(updated.status()).isEqualTo(LegalCaseStatus.CLASSIFYING));
        assertThat(statusHistoryRepository.all()).singleElement().satisfies(entry -> {
            assertThat(entry.previousStatus()).isEqualTo(LegalCaseStatus.RECEIVED);
            assertThat(entry.newStatus()).isEqualTo(LegalCaseStatus.CLASSIFYING);
            assertThat(entry.changedBy()).isEqualTo(LegalCaseStatusHistoryEntry.SYSTEM_ACTOR);
            assertThat(entry.reason()).contains(LegalCaseReceivedEvent.EVENT_TYPE);
        });
        assertThat(processingEventStore.stateOf(event.idempotencyKey())).isEqualTo(State.PROCESSED);
        // Transição e conclusão do evento em uma unidade de trabalho só.
        assertThat(transactionRunner.transactionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("o mesmo evento processado duas vezes não avança a demanda de novo")
    void shouldIgnoreAlreadyProcessedEvent() {
        LegalCase legalCase = givenLegalCase(LegalCaseStatus.RECEIVED);
        LegalCaseReceivedEvent event = eventFor(legalCase.id());
        service.process(event);

        LegalCaseProcessingOutcome outcome = service.process(event);

        assertThat(outcome).isEqualTo(LegalCaseProcessingOutcome.SKIPPED_ALREADY_PROCESSED);
        assertThat(outcome.isSkipped()).isTrue();
        assertThat(statusHistoryRepository.all()).hasSize(1);
        assertThat(legalCaseRepository.findById(legalCase.id()))
                .get()
                .satisfies(updated -> assertThat(updated.status()).isEqualTo(LegalCaseStatus.CLASSIFYING));
    }

    @Test
    @DisplayName("evento reservado por outra réplica é descartado sem efeito")
    void shouldSkipEventInProgressElsewhere() {
        LegalCase legalCase = givenLegalCase(LegalCaseStatus.RECEIVED);
        LegalCaseReceivedEvent event = eventFor(legalCase.id());
        processingEventStore.put(event.idempotencyKey(), State.IN_PROGRESS);

        LegalCaseProcessingOutcome outcome = service.process(event);

        assertThat(outcome).isEqualTo(LegalCaseProcessingOutcome.SKIPPED_IN_PROGRESS);
        assertThat(statusHistoryRepository.all()).isEmpty();
        assertThat(legalCaseRepository.findById(legalCase.id()))
                .get()
                .satisfies(updated -> assertThat(updated.status()).isEqualTo(LegalCaseStatus.RECEIVED));
    }

    @Test
    @DisplayName("uma tentativa que falhou antes é retomada na entrega seguinte")
    void shouldRetryPreviouslyFailedEvent() {
        LegalCase legalCase = givenLegalCase(LegalCaseStatus.RECEIVED);
        LegalCaseReceivedEvent event = eventFor(legalCase.id());
        processingEventStore.put(event.idempotencyKey(), State.FAILED);

        assertThat(service.process(event)).isEqualTo(LegalCaseProcessingOutcome.PROCESSED);
        assertThat(processingEventStore.stateOf(event.idempotencyKey())).isEqualTo(State.PROCESSED);
    }

    @Test
    @DisplayName("evento de demanda inexistente falha e fica marcado para nova tentativa")
    void shouldMarkFailedWhenLegalCaseDoesNotExist() {
        LegalCaseReceivedEvent event = eventFor(UUID.randomUUID());

        assertThatExceptionOfType(LegalCaseNotFoundException.class).isThrownBy(() -> service.process(event));

        // Não marcado como processado: é isso que permite o retry e, esgotado, a dead-letter.
        assertThat(processingEventStore.stateOf(event.idempotencyKey())).isEqualTo(State.FAILED);
        assertThat(statusHistoryRepository.all()).isEmpty();
    }

    @Test
    @DisplayName("demanda que já saiu de RECEIVED faz o evento falhar, em vez de forçar a transição")
    void shouldMarkFailedWhenTransitionIsNotAllowed() {
        LegalCase legalCase = givenLegalCase(LegalCaseStatus.CLASSIFYING);
        LegalCaseReceivedEvent event = eventFor(legalCase.id());

        assertThatExceptionOfType(InvalidStatusTransitionException.class).isThrownBy(() -> service.process(event));

        assertThat(processingEventStore.stateOf(event.idempotencyKey())).isEqualTo(State.FAILED);
        assertThat(legalCaseRepository.findById(legalCase.id()))
                .get()
                .satisfies(updated -> assertThat(updated.status()).isEqualTo(LegalCaseStatus.CLASSIFYING));
    }
}
