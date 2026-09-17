package com.lexflow.application.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.lexflow.application.exception.IdempotentRequestInProgressException;
import com.lexflow.application.idempotency.IdempotencyNamespace;
import com.lexflow.application.legalcase.LegalCaseStatusTransitionService;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.DirectTransactionRunner;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryIdempotencyStore;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryLegalCaseRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryStatusHistoryRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.SequentialIdGenerator;
import com.lexflow.application.review.support.ReviewTestDoubles.InMemoryDecisionRepository;
import com.lexflow.application.review.support.ReviewTestDoubles.RecordingDecisionEventPublisher;
import com.lexflow.domain.decision.Decision;
import com.lexflow.domain.decision.DecisionType;
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
 * Registro da decisão humana (Prompt 15).
 *
 * <p>O que estes testes fixam: os três desfechos levam a demanda ao status correspondente, a decisão
 * e a transição andam juntas, e reenviar a mesma chave não cria nada novo.
 */
class RegisterDecisionServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    private InMemoryLegalCaseRepository legalCaseRepository;
    private InMemoryDecisionRepository decisionRepository;
    private InMemoryStatusHistoryRepository historyRepository;
    private InMemoryIdempotencyStore idempotencyStore;
    private RecordingDecisionEventPublisher eventPublisher;
    private RegisterDecisionService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SequentialIdGenerator ids = new SequentialIdGenerator();
        legalCaseRepository = new InMemoryLegalCaseRepository();
        decisionRepository = new InMemoryDecisionRepository();
        historyRepository = new InMemoryStatusHistoryRepository();
        idempotencyStore = new InMemoryIdempotencyStore();
        eventPublisher = new RecordingDecisionEventPublisher();
        service = new RegisterDecisionService(
                legalCaseRepository,
                decisionRepository,
                new LegalCaseStatusTransitionService(new LegalCaseStatusTransitionRules(), clock, ids),
                historyRepository,
                idempotencyStore,
                eventPublisher,
                new DirectTransactionRunner(),
                clock,
                ids);
    }

    @Test
    @DisplayName("aprovar leva a demanda para APPROVED, com a decisão e o histórico gravados")
    void shouldRegisterApproval() {
        LegalCase legalCase = givenCaseAwaitingReview();

        RegisterDecisionResult result = service.register(new RegisterDecisionCommand(
                legalCase.id(), DecisionType.APPROVED, "ana.silva", "Alçada conferida.", null));

        assertThat(result.repeated()).isFalse();
        assertThat(result.decision().decisionType()).isEqualTo(DecisionType.APPROVED);
        assertThat(result.decision().decidedBy()).isEqualTo("ana.silva");
        assertThat(result.legalCase().status()).isEqualTo(LegalCaseStatus.APPROVED);
        assertThat(legalCaseRepository.findById(legalCase.id()).orElseThrow().status())
                .isEqualTo(LegalCaseStatus.APPROVED);
        assertThat(historyRepository.findByLegalCaseId(legalCase.id()))
                .anySatisfy(entry -> {
                    assertThat(entry.newStatus()).isEqualTo(LegalCaseStatus.APPROVED);
                    assertThat(entry.changedBy()).isEqualTo("ana.silva");
                    assertThat(entry.reason()).contains("APPROVED", "ana.silva");
                });
    }

    @Test
    @DisplayName("reprovar e devolver levam aos status correspondentes")
    void shouldRegisterRejectionAndReturn() {
        LegalCase rejected = givenCaseAwaitingReview();
        LegalCase returned = givenCaseAwaitingReview();

        service.register(new RegisterDecisionCommand(rejected.id(), DecisionType.REJECTED, "ana.silva", null, null));
        service.register(new RegisterDecisionCommand(
                returned.id(), DecisionType.RETURNED_FOR_CORRECTION, "ana.silva", "Falta o parecer financeiro.", null));

        assertThat(legalCaseRepository.findById(rejected.id()).orElseThrow().status())
                .isEqualTo(LegalCaseStatus.REJECTED);
        assertThat(legalCaseRepository.findById(returned.id()).orElseThrow().status())
                .isEqualTo(LegalCaseStatus.RETURNED_FOR_CORRECTION);
    }

    @Test
    @DisplayName("uma devolução sem comentários é recusada: ela precisa dizer o que falta")
    void shouldRequireCommentsOnReturn() {
        LegalCase legalCase = givenCaseAwaitingReview();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.register(new RegisterDecisionCommand(
                        legalCase.id(), DecisionType.RETURNED_FOR_CORRECTION, "ana.silva", null, null)))
                .withMessageContaining("comentários");
        assertThat(decisionRepository.size()).isZero();
        assertThat(legalCaseRepository.findById(legalCase.id()).orElseThrow().status())
                .isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW);
    }

    @Test
    @DisplayName("critério de aceite: a mesma chave de idempotência não gera duas decisões nem duas transições")
    void shouldNotRegisterTheSameDecisionTwice() {
        LegalCase legalCase = givenCaseAwaitingReview();
        RegisterDecisionCommand command = new RegisterDecisionCommand(
                legalCase.id(), DecisionType.APPROVED, "ana.silva", "Alçada conferida.", "chave-1");

        RegisterDecisionResult first = service.register(command);
        RegisterDecisionResult second = service.register(command);

        assertThat(first.repeated()).isFalse();
        assertThat(second.repeated()).isTrue();
        assertThat(second.decision().id()).isEqualTo(first.decision().id());
        assertThat(decisionRepository.size()).isEqualTo(1);
        assertThat(historyRepository.findByLegalCaseId(legalCase.id()))
                .filteredOn(entry -> entry.newStatus() == LegalCaseStatus.APPROVED)
                .hasSize(1);
        // O evento também sai uma vez só: quem consome não vê a decisão em duplicata.
        assertThat(eventPublisher.events()).hasSize(1);
    }

    @Test
    @DisplayName("uma chave reservada por uma requisição que não terminou resulta em conflito")
    void shouldRejectKeyReservedByUnfinishedRequest() {
        LegalCase legalCase = givenCaseAwaitingReview();
        idempotencyStore.reserve(IdempotencyNamespace.LEGAL_CASE_DECISION, "chave-presa", legalCase.id());

        assertThatExceptionOfType(IdempotentRequestInProgressException.class)
                .isThrownBy(() -> service.register(new RegisterDecisionCommand(
                        legalCase.id(), DecisionType.APPROVED, "ana.silva", null, "chave-presa")));
        assertThat(decisionRepository.size()).isZero();
    }

    @Test
    @DisplayName("a mesma chave usada na ingestão não é confundida com a de uma decisão")
    void shouldIsolateKeysByNamespace() {
        LegalCase legalCase = givenCaseAwaitingReview();
        idempotencyStore.reserve(IdempotencyNamespace.LEGAL_CASE_INGESTION, "mesma-chave", legalCase.id());

        RegisterDecisionResult result = service.register(new RegisterDecisionCommand(
                legalCase.id(), DecisionType.APPROVED, "ana.silva", null, "mesma-chave"));

        assertThat(result.repeated()).isFalse();
        assertThat(decisionRepository.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("o evento publicado traz o desfecho e nenhum comentário")
    void shouldPublishEventWithoutComments() {
        LegalCase legalCase = givenCaseAwaitingReview();

        service.register(new RegisterDecisionCommand(
                legalCase.id(), DecisionType.APPROVED, "ana.silva", "Comentário confidencial.", null));

        assertThat(eventPublisher.events()).singleElement().satisfies(event -> {
            assertThat(event.legalCaseId()).isEqualTo(legalCase.id());
            assertThat(event.decisionType()).isEqualTo(DecisionType.APPROVED);
            assertThat(event.resultingStatus()).isEqualTo(LegalCaseStatus.APPROVED);
            assertThat(event.decidedBy()).isEqualTo("ana.silva");
            assertThat(event.idempotencyKey()).startsWith(DecisionRegisteredEvent.EVENT_TYPE + ":");
            assertThat(event.toString()).doesNotContain("Comentário confidencial");
        });
    }

    @Test
    @DisplayName("decidir uma demanda fora da revisão humana é recusado, sem gravar nada")
    void shouldRejectDecisionOutsideHumanReview() {
        LegalCase extracting = givenCase(LegalCaseStatus.EXTRACTING);

        assertThatExceptionOfType(InvalidStatusTransitionException.class)
                .isThrownBy(() -> service.register(new RegisterDecisionCommand(
                        extracting.id(), DecisionType.APPROVED, "ana.silva", null, null)));
        assertThatExceptionOfType(LegalCaseNotFoundException.class)
                .isThrownBy(() -> service.register(new RegisterDecisionCommand(
                        UUID.randomUUID(), DecisionType.APPROVED, "ana.silva", null, null)));
        assertThat(decisionRepository.size()).isZero();
        assertThat(eventPublisher.events()).isEmpty();
    }

    @Test
    @DisplayName("toda decisão tem um responsável identificado")
    void shouldRequireDecidedBy() {
        LegalCase legalCase = givenCaseAwaitingReview();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RegisterDecisionCommand(legalCase.id(), DecisionType.APPROVED, "  ", null, null))
                .withMessageContaining("decidedBy");
    }

    @Test
    @DisplayName("as decisões de uma demanda são listadas da mais antiga para a mais recente")
    void shouldListDecisions() {
        LegalCase legalCase = givenCaseAwaitingReview();
        service.register(new RegisterDecisionCommand(
                legalCase.id(), DecisionType.RETURNED_FOR_CORRECTION, "ana.silva", "Falta documento.", null));

        assertThat(service.findByLegalCaseId(legalCase.id()))
                .extracting(Decision::decisionType)
                .containsExactly(DecisionType.RETURNED_FOR_CORRECTION);
    }

    private LegalCase givenCaseAwaitingReview() {
        return givenCase(LegalCaseStatus.PENDING_HUMAN_REVIEW);
    }

    private LegalCase givenCase(LegalCaseStatus status) {
        LegalCase legalCase = LegalCase.receive(
                UUID.randomUUID(), null, LegalCaseType.CONTRACT_SIGNING, "requisitante", CasePriority.NORMAL, NOW);
        LegalCaseStatusTransitionRules anyTransition = new LegalCaseStatusTransitionRules() {
            @Override
            public boolean isAllowed(LegalCaseStatus current, LegalCaseStatus target) {
                return true;
            }
        };
        if (status != LegalCaseStatus.RECEIVED) {
            legalCase = legalCase.transitionTo(status, NOW, anyTransition);
        }
        return legalCaseRepository.save(legalCase);
    }
}
