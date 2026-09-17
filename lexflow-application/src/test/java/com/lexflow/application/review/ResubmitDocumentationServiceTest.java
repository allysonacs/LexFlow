package com.lexflow.application.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.lexflow.application.analysis.support.AnalysisTestDoubles.InMemoryAiAnalysisResponseRepository;
import com.lexflow.application.document.DocumentUpload;
import com.lexflow.application.document.StoreDocumentsService;
import com.lexflow.application.legalcase.LegalCaseStatusTransitionService;
import com.lexflow.application.legalcase.LegalCaseWithDocuments;
import com.lexflow.application.legalcase.support.FactExtractionTestDoubles.InMemoryLegalCaseAlertRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.DirectTransactionRunner;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryDocumentRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryLegalCaseRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryStatusHistoryRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.RecordingDocumentStorage;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.RecordingEventPublisher;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.SequentialIdGenerator;
import com.lexflow.domain.ai.AiAnalysisResponse;
import com.lexflow.domain.ai.QuestionKey;
import com.lexflow.domain.alert.LegalCaseAlert;
import com.lexflow.domain.alert.LegalCaseAlertType;
import com.lexflow.domain.exception.InvalidStatusTransitionException;
import com.lexflow.domain.exception.UnsupportedDocumentFormatException;
import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseStatusTransitionRules;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reabertura de uma demanda devolvida para correção (Prompt 15, item 4).
 *
 * <p>O que estes testes fixam: o reenvio devolve a demanda ao início do pipeline em condição de ser
 * analisada de novo — sem a análise antiga, sem os alertas que o reenvio responde e com o evento
 * republicado.
 */
class ResubmitDocumentationServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    private InMemoryLegalCaseRepository legalCaseRepository;
    private InMemoryDocumentRepository documentRepository;
    private InMemoryAiAnalysisResponseRepository responseRepository;
    private InMemoryLegalCaseAlertRepository alertRepository;
    private InMemoryStatusHistoryRepository historyRepository;
    private RecordingDocumentStorage storage;
    private RecordingEventPublisher eventPublisher;
    private ResubmitDocumentationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SequentialIdGenerator ids = new SequentialIdGenerator();
        legalCaseRepository = new InMemoryLegalCaseRepository();
        documentRepository = new InMemoryDocumentRepository();
        responseRepository = new InMemoryAiAnalysisResponseRepository();
        alertRepository = new InMemoryLegalCaseAlertRepository();
        historyRepository = new InMemoryStatusHistoryRepository();
        storage = new RecordingDocumentStorage();
        eventPublisher = new RecordingEventPublisher();
        service = new ResubmitDocumentationService(
                legalCaseRepository,
                documentRepository,
                responseRepository,
                alertRepository,
                new LegalCaseStatusTransitionService(new LegalCaseStatusTransitionRules(), clock, ids),
                historyRepository,
                new StoreDocumentsService(storage, ids),
                eventPublisher,
                new DirectTransactionRunner(),
                clock,
                ids);
    }

    @Test
    @DisplayName("o reenvio anexa a documentação, devolve a demanda a RECEIVED e republica o evento")
    void shouldReopenTheCase() {
        LegalCase legalCase = givenReturnedCase();

        LegalCaseWithDocuments result =
                service.resubmit(legalCase.id(), List.of(upload("parecer.pdf", "conteúdo")), "requisitante");

        assertThat(result.legalCase().status()).isEqualTo(LegalCaseStatus.RECEIVED);
        assertThat(result.documents()).hasSize(1);
        assertThat(legalCaseRepository.findById(legalCase.id()).orElseThrow().status())
                .isEqualTo(LegalCaseStatus.RECEIVED);
        assertThat(historyRepository.findByLegalCaseId(legalCase.id()))
                .anySatisfy(entry -> {
                    assertThat(entry.newStatus()).isEqualTo(LegalCaseStatus.RECEIVED);
                    assertThat(entry.changedBy()).isEqualTo("requisitante");
                    assertThat(entry.reason()).contains("Documentação reenviada");
                });
        assertThat(eventPublisher.published()).singleElement().satisfies(event -> {
            assertThat(event.legalCaseId()).isEqualTo(legalCase.id());
            assertThat(event.documentCount()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("as respostas antigas da IA são descartadas: elas falavam de outra documentação")
    void shouldDiscardPreviousAnalysis() {
        LegalCase legalCase = givenReturnedCase();
        responseRepository.save(AiAnalysisResponse.deterministic(
                UUID.randomUUID(),
                legalCase.id(),
                QuestionKey.HAS_SUFFICIENT_DOCUMENTATION,
                "Documentação incompleta.",
                NOW));

        service.resubmit(legalCase.id(), List.of(upload("parecer.pdf", "conteúdo")), "requisitante");

        assertThat(responseRepository.findByLegalCaseId(legalCase.id())).isEmpty();
    }

    @Test
    @DisplayName("os alertas em aberto são resolvidos: é o reenvio que responde a eles")
    void shouldResolveOpenAlerts() {
        LegalCase legalCase = givenReturnedCase();
        alertRepository.save(LegalCaseAlert.open(
                UUID.randomUUID(),
                legalCase.id(),
                null,
                LegalCaseAlertType.AI_ANALYSIS_INVALID_OUTPUT,
                "Resposta fora do formato.",
                NOW));

        service.resubmit(legalCase.id(), List.of(upload("parecer.pdf", "conteúdo")), "requisitante");

        assertThat(alertRepository.findByLegalCaseId(legalCase.id()))
                .allSatisfy(alert -> assertThat(alert.isOpen()).isFalse());
    }

    @Test
    @DisplayName("um arquivo idêntico a um já anexado não vira um documento novo")
    void shouldIgnoreDuplicatedFile() {
        LegalCase legalCase = givenReturnedCase();
        service.resubmit(legalCase.id(), List.of(upload("parecer.pdf", "conteúdo")), "requisitante");
        legalCaseRepository.save(legalCaseRepository
                .findById(legalCase.id())
                .orElseThrow()
                .transitionTo(LegalCaseStatus.RETURNED_FOR_CORRECTION, NOW, anyTransition()));

        service.resubmit(legalCase.id(), List.of(upload("parecer.pdf", "conteúdo")), "requisitante");

        assertThat(documentRepository.findByLegalCaseId(legalCase.id())).hasSize(1);
    }

    @Test
    @DisplayName("reenviar para uma demanda que não foi devolvida é recusado")
    void shouldRejectResubmissionOutsideReturnedForCorrection() {
        LegalCase legalCase = givenCase(LegalCaseStatus.PENDING_HUMAN_REVIEW);

        assertThatExceptionOfType(InvalidStatusTransitionException.class)
                .isThrownBy(() ->
                        service.resubmit(legalCase.id(), List.of(upload("parecer.pdf", "conteúdo")), "requisitante"));
        assertThat(documentRepository.findByLegalCaseId(legalCase.id())).isEmpty();
        assertThat(eventPublisher.published()).isEmpty();
    }

    @Test
    @DisplayName("um formato não aceito é recusado antes de qualquer gravação")
    void shouldRejectUnsupportedFormatBeforeStoring() {
        LegalCase legalCase = givenReturnedCase();

        assertThatExceptionOfType(UnsupportedDocumentFormatException.class)
                .isThrownBy(() -> service.resubmit(legalCase.id(), List.of(upload("parecer.exe", "x")), "requisitante"));
        assertThat(storage.objectCount()).isZero();
        assertThat(legalCaseRepository.findById(legalCase.id()).orElseThrow().status())
                .isEqualTo(LegalCaseStatus.RETURNED_FOR_CORRECTION);
    }

    @Test
    @DisplayName("reenvio sem arquivo e sem responsável são recusados")
    void shouldRejectEmptyResubmission() {
        LegalCase legalCase = givenReturnedCase();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resubmit(legalCase.id(), List.of(), "requisitante"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resubmit(legalCase.id(), List.of(upload("a.pdf", "x")), " "));
    }

    private static DocumentUpload upload(String fileName, String content) {
        return new DocumentUpload(fileName, null, content.getBytes(StandardCharsets.UTF_8));
    }

    private LegalCase givenReturnedCase() {
        return givenCase(LegalCaseStatus.RETURNED_FOR_CORRECTION);
    }

    private LegalCase givenCase(LegalCaseStatus status) {
        LegalCase legalCase = LegalCase.receive(
                UUID.randomUUID(), null, LegalCaseType.CONTRACT_SIGNING, "requisitante", CasePriority.NORMAL, NOW);
        if (status != LegalCaseStatus.RECEIVED) {
            legalCase = legalCase.transitionTo(status, NOW, anyTransition());
        }
        return legalCaseRepository.save(legalCase);
    }

    private static LegalCaseStatusTransitionRules anyTransition() {
        return new LegalCaseStatusTransitionRules() {
            @Override
            public boolean isAllowed(LegalCaseStatus current, LegalCaseStatus target) {
                return true;
            }
        };
    }
}
