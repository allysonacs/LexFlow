package com.lexflow.application.legalcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.application.checklist.DocumentChecklistService;
import com.lexflow.application.document.DocumentUpload;
import com.lexflow.application.document.ExtractDocumentTextService;
import com.lexflow.application.exception.DocumentTextExtractionException;
import com.lexflow.application.exception.UnreadableDocumentException;
import com.lexflow.application.legalcase.support.ChecklistTestDoubles;
import com.lexflow.application.legalcase.support.ChecklistTestDoubles.InMemoryChecklistRuleRepository;
import com.lexflow.application.legalcase.support.ChecklistTestDoubles.InMemoryDocumentChecklistItemRepository;
import com.lexflow.application.legalcase.support.ExtractionTestDoubles.InMemoryDocumentTextContentRepository;
import com.lexflow.application.legalcase.support.ExtractionTestDoubles.ScriptedTextExtractor;
import com.lexflow.application.legalcase.support.InMemoryProcessingEventStore;
import com.lexflow.application.legalcase.support.InMemoryProcessingEventStore.State;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.DirectTransactionRunner;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryDocumentRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryLegalCaseRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryStatusHistoryRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.RecordingDocumentStorage;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.SequentialIdGenerator;
import com.lexflow.domain.checklist.ChecklistItemStatus;
import com.lexflow.domain.classification.ClassificationOutcome;
import com.lexflow.domain.classification.LegalCaseKeywordClassifier;
import com.lexflow.domain.document.Document;
import com.lexflow.domain.document.DocumentFormat;
import com.lexflow.domain.document.Sha256Checksum;
import com.lexflow.domain.document.TextExtractionStatus;
import com.lexflow.domain.exception.InvalidStatusTransitionException;
import com.lexflow.domain.exception.LegalCaseNotFoundException;
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
 * Cobre o consumo do evento sem broker nenhum.
 *
 * <p>É o contraponto rápido ao teste de integração com RabbitMQ: aqui se verifica a decisão —
 * reservar, classificar, extrair, descartar, retomar ou falhar —, lá se verifica a entrega.
 */
class ProcessLegalCaseReceivedEventServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    private InMemoryLegalCaseRepository legalCaseRepository;
    private InMemoryDocumentRepository documentRepository;
    private InMemoryStatusHistoryRepository statusHistoryRepository;
    private InMemoryDocumentTextContentRepository textContentRepository;
    private RecordingDocumentStorage storage;
    private ScriptedTextExtractor extractor;
    private InMemoryDocumentChecklistItemRepository checklistItemRepository;
    private InMemoryProcessingEventStore processingEventStore;
    private DirectTransactionRunner transactionRunner;
    private ProcessLegalCaseReceivedEventService service;

    @BeforeEach
    void setUp() {
        legalCaseRepository = new InMemoryLegalCaseRepository();
        documentRepository = new InMemoryDocumentRepository();
        statusHistoryRepository = new InMemoryStatusHistoryRepository();
        textContentRepository = new InMemoryDocumentTextContentRepository();
        storage = new RecordingDocumentStorage();
        extractor = new ScriptedTextExtractor();
        processingEventStore = new InMemoryProcessingEventStore();
        transactionRunner = new DirectTransactionRunner();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SequentialIdGenerator ids = new SequentialIdGenerator();
        checklistItemRepository = new InMemoryDocumentChecklistItemRepository();
        InMemoryChecklistRuleRepository ruleRepository = new InMemoryChecklistRuleRepository();
        ChecklistTestDoubles.seedRules().forEach(ruleRepository::save);
        service = new ProcessLegalCaseReceivedEventService(
                legalCaseRepository,
                documentRepository,
                statusHistoryRepository,
                new LegalCaseStatusTransitionService(new LegalCaseStatusTransitionRules(), clock, ids),
                new LegalCaseKeywordClassifier(),
                new DocumentChecklistService(
                        legalCaseRepository, documentRepository, ruleRepository, checklistItemRepository, clock, ids),
                new ExtractDocumentTextService(
                        documentRepository, textContentRepository, storage, extractor, clock, ids),
                processingEventStore,
                transactionRunner);
    }

    private LegalCase givenLegalCase(LegalCaseType type, String description, LegalCaseStatus status) {
        LegalCase legalCase = LegalCase.receive(
                UUID.randomUUID(), "REF-1", type, "ana.silva", description, CasePriority.NORMAL, NOW);
        LegalCaseStatusTransitionRules permissive = new LegalCaseStatusTransitionRules() {
            @Override
            public boolean isAllowed(LegalCaseStatus current, LegalCaseStatus target) {
                return true;
            }
        };
        if (status != LegalCaseStatus.RECEIVED) {
            legalCase = legalCase.transitionTo(status, NOW, permissive);
        }
        return legalCaseRepository.save(legalCase);
    }

    private LegalCase givenLegalCase(LegalCaseStatus status) {
        return givenLegalCase(LegalCaseType.CONTRACT_SIGNING, null, status);
    }

    private void givenDocument(LegalCase legalCase, String fileName, String content) {
        givenDocument(legalCase, fileName, content, null);
    }

    private void givenDocument(LegalCase legalCase, String fileName, String content, String documentType) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        DocumentFormat format = DocumentFormat.ofFileName(fileName);
        Sha256Checksum checksum = Sha256Checksum.ofContent(bytes);
        String path = storage.store(legalCase.id(), format, checksum, new DocumentUpload(fileName, null, bytes))
                .storagePath();
        documentRepository.saveAll(List.of(new Document(
                UUID.randomUUID(), legalCase.id(), fileName, path, format.canonicalMimeType(), checksum, NOW,
                documentType)));
    }

    private static LegalCaseReceivedEvent eventFor(UUID legalCaseId) {
        return LegalCaseReceivedEvent.of(
                UUID.randomUUID(), legalCaseId, LegalCaseType.CONTRACT_SIGNING, CasePriority.NORMAL, 1, NOW);
    }

    private LegalCaseStatus statusOf(LegalCase legalCase) {
        return legalCaseRepository.findById(legalCase.id()).orElseThrow().status();
    }

    @Test
    @DisplayName("a demanda é classificada, vai até EXTRACTING e tem o texto dos documentos gravado")
    void shouldClassifyAndExtractText() {
        LegalCase legalCase = givenLegalCase(LegalCaseType.CONTRACT_SIGNING, "Assinatura do contrato", LegalCaseStatus.RECEIVED);
        givenDocument(legalCase, "minuta_contrato.pdf", "Cláusula primeira", "CONTRACT_DRAFT");
        givenDocument(legalCase, "procuracao.png", "Outorgante", "SIGNATORY_POWERS");
        LegalCaseReceivedEvent event = eventFor(legalCase.id());

        LegalCaseProcessingResult result = service.process(event);

        assertThat(result.outcome()).isEqualTo(LegalCaseProcessingOutcome.PROCESSED);
        assertThat(result.isSkipped()).isFalse();
        assertThat(result.classificationIfPerformed()).get().satisfies(classification -> {
            assertThat(classification.outcome()).isEqualTo(ClassificationOutcome.CONFIRMED);
            assertThat(classification.resolvedType()).isEqualTo(LegalCaseType.CONTRACT_SIGNING);
        });
        assertThat(result.countByStatus(TextExtractionStatus.EXTRACTED)).isEqualTo(2);
        assertThat(statusOf(legalCase)).isEqualTo(LegalCaseStatus.EXTRACTING);
        // Checklist gerado na mesma etapa: a minuta satisfaz seu item, o parecer financeiro falta.
        assertThat(result.checklistIfEvaluated()).get().satisfies(checklist -> {
            assertThat(checklist.items()).hasSize(3);
            assertThat(checklist.hasSufficientDocumentation()).isFalse();
            assertThat(checklist.missingMandatoryDocumentTypes()).containsExactly("FINANCIAL_OPINION");
        });
        assertThat(checklistItemRepository.findByLegalCaseId(legalCase.id()))
                .filteredOn(item -> item.status() == ChecklistItemStatus.SATISFIED)
                .hasSize(2);
        assertThat(textContentRepository.findByLegalCaseId(legalCase.id())).hasSize(2);

        assertThat(statusHistoryRepository.all()).hasSize(2);
        assertThat(statusHistoryRepository.all().get(0)).satisfies(entry -> {
            assertThat(entry.previousStatus()).isEqualTo(LegalCaseStatus.RECEIVED);
            assertThat(entry.newStatus()).isEqualTo(LegalCaseStatus.CLASSIFYING);
            assertThat(entry.changedBy()).isEqualTo(LegalCaseStatusHistoryEntry.SYSTEM_ACTOR);
            assertThat(entry.reason()).contains(LegalCaseReceivedEvent.EVENT_TYPE);
        });
        assertThat(statusHistoryRepository.all().get(1)).satisfies(entry -> {
            assertThat(entry.previousStatus()).isEqualTo(LegalCaseStatus.CLASSIFYING);
            assertThat(entry.newStatus()).isEqualTo(LegalCaseStatus.EXTRACTING);
            assertThat(entry.reason())
                    .startsWith("Classificação concluída")
                    .contains("CONTRACT_SIGNING informado e confirmado")
                    .contains("minuta")
                    .contains("assinatura");
        });
        assertThat(processingEventStore.stateOf(event.idempotencyKey())).isEqualTo(State.PROCESSED);
        // As duas transições em uma unidade de trabalho só; a extração fica fora dela.
        assertThat(transactionRunner.transactionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("nunca avança para AI_ANALYSIS_IN_PROGRESS: isso é da extração de fatos")
    void shouldStopAtExtracting() {
        LegalCase legalCase = givenLegalCase(LegalCaseStatus.RECEIVED);
        givenDocument(legalCase, "contrato.pdf", "texto");

        service.process(eventFor(legalCase.id()));

        assertThat(statusOf(legalCase)).isEqualTo(LegalCaseStatus.EXTRACTING);
        assertThat(statusHistoryRepository.all())
                .extracting(LegalCaseStatusHistoryEntry::newStatus)
                .doesNotContain(LegalCaseStatus.AI_ANALYSIS_IN_PROGRESS);
    }

    @Test
    @DisplayName("divergência entre o tipo informado e as palavras-chave vai para o histórico, sem trocar o tipo")
    void shouldRecordDivergentClassification() {
        LegalCase legalCase = givenLegalCase(LegalCaseType.PROPOSAL_ACCEPTANCE, null, LegalCaseStatus.RECEIVED);
        givenDocument(legalCase, "termo_de_acordo.pdf", "texto");

        LegalCaseProcessingResult result = service.process(eventFor(legalCase.id()));

        assertThat(result.classification().outcome()).isEqualTo(ClassificationOutcome.DIVERGENT);
        assertThat(legalCaseRepository.findById(legalCase.id()).orElseThrow().caseType())
                .isEqualTo(LegalCaseType.PROPOSAL_ACCEPTANCE);
        assertThat(statusHistoryRepository.all().get(1).reason())
                .contains("SETTLEMENT_PAYMENT")
                .contains("revisar");
    }

    @Test
    @DisplayName("documento ilegível não impede o avanço da demanda")
    void shouldAdvanceEvenWithUnreadableDocument() {
        LegalCase legalCase = givenLegalCase(LegalCaseStatus.RECEIVED);
        givenDocument(legalCase, "corrompido.pdf", "lixo");
        extractor.willAnswer((format, content) -> {
            throw new UnreadableDocumentException("PDF inválido");
        });

        LegalCaseProcessingResult result = service.process(eventFor(legalCase.id()));

        assertThat(result.outcome()).isEqualTo(LegalCaseProcessingOutcome.PROCESSED);
        assertThat(result.countByStatus(TextExtractionStatus.FAILED)).isEqualTo(1);
        assertThat(statusOf(legalCase)).isEqualTo(LegalCaseStatus.EXTRACTING);
    }

    @Test
    @DisplayName("falha de ambiente na extração marca o evento como falho, com a demanda já classificada")
    void shouldMarkFailedWhenExtractionEnvironmentFails() {
        LegalCase legalCase = givenLegalCase(LegalCaseStatus.RECEIVED);
        givenDocument(legalCase, "scan.png", "imagem");
        extractor.willAnswer((format, content) -> {
            throw new DocumentTextExtractionException("Tesseract não está instalado");
        });
        LegalCaseReceivedEvent event = eventFor(legalCase.id());

        assertThatExceptionOfType(DocumentTextExtractionException.class).isThrownBy(() -> service.process(event));

        assertThat(processingEventStore.stateOf(event.idempotencyKey())).isEqualTo(State.FAILED);
        // A classificação já foi confirmada: a próxima tentativa retoma da extração.
        assertThat(statusOf(legalCase)).isEqualTo(LegalCaseStatus.EXTRACTING);
        assertThat(textContentRepository.all()).isEmpty();
    }

    @Test
    @DisplayName("a nova entrega de um evento que falhou na extração retoma sem reclassificar")
    void shouldResumeFromExtractingOnRetry() {
        LegalCase legalCase = givenLegalCase(LegalCaseStatus.RECEIVED);
        givenDocument(legalCase, "scan.png", "imagem");
        extractor.willAnswer((format, content) -> {
            throw new DocumentTextExtractionException("tempo esgotado");
        });
        LegalCaseReceivedEvent event = eventFor(legalCase.id());
        assertThatExceptionOfType(DocumentTextExtractionException.class).isThrownBy(() -> service.process(event));

        extractor.willAnswer(ScriptedTextExtractor::echo);
        LegalCaseProcessingResult result = service.process(event);

        assertThat(result.outcome()).isEqualTo(LegalCaseProcessingOutcome.PROCESSED);
        assertThat(result.classificationIfPerformed()).isEmpty();
        assertThat(result.checklistIfEvaluated()).get().satisfies(checklist -> assertThat(checklist.items()).hasSize(3));
        assertThat(checklistItemRepository.all()).hasSize(3);
        assertThat(result.countByStatus(TextExtractionStatus.EXTRACTED)).isEqualTo(1);
        assertThat(statusHistoryRepository.all()).hasSize(2);
        assertThat(processingEventStore.stateOf(event.idempotencyKey())).isEqualTo(State.PROCESSED);
    }

    @Test
    @DisplayName("demanda que ficou em CLASSIFYING (versão anterior do pipeline) é classificada sem repetir a entrada")
    void shouldResumeFromClassifying() {
        LegalCase legalCase = givenLegalCase(LegalCaseStatus.CLASSIFYING);
        givenDocument(legalCase, "contrato.pdf", "texto");

        LegalCaseProcessingResult result = service.process(eventFor(legalCase.id()));

        assertThat(result.classificationIfPerformed()).isPresent();
        assertThat(statusOf(legalCase)).isEqualTo(LegalCaseStatus.EXTRACTING);
        assertThat(statusHistoryRepository.all()).singleElement().satisfies(entry -> {
            assertThat(entry.previousStatus()).isEqualTo(LegalCaseStatus.CLASSIFYING);
            assertThat(entry.newStatus()).isEqualTo(LegalCaseStatus.EXTRACTING);
        });
    }

    @Test
    @DisplayName("o mesmo evento processado duas vezes não refaz nada")
    void shouldIgnoreAlreadyProcessedEvent() {
        LegalCase legalCase = givenLegalCase(LegalCaseStatus.RECEIVED);
        givenDocument(legalCase, "contrato.pdf", "texto");
        LegalCaseReceivedEvent event = eventFor(legalCase.id());
        service.process(event);

        LegalCaseProcessingResult result = service.process(event);

        assertThat(result.outcome()).isEqualTo(LegalCaseProcessingOutcome.SKIPPED_ALREADY_PROCESSED);
        assertThat(result.isSkipped()).isTrue();
        assertThat(result.classification()).isNull();
        assertThat(result.checklistIfEvaluated()).isEmpty();
        assertThat(result.textContents()).isEmpty();
        assertThat(statusHistoryRepository.all()).hasSize(2);
        assertThat(extractor.calls()).hasSize(1);
        assertThat(statusOf(legalCase)).isEqualTo(LegalCaseStatus.EXTRACTING);
    }

    @Test
    @DisplayName("evento reservado por outra réplica é descartado sem efeito")
    void shouldSkipEventInProgressElsewhere() {
        LegalCase legalCase = givenLegalCase(LegalCaseStatus.RECEIVED);
        LegalCaseReceivedEvent event = eventFor(legalCase.id());
        processingEventStore.put(event.idempotencyKey(), State.IN_PROGRESS);

        LegalCaseProcessingResult result = service.process(event);

        assertThat(result.outcome()).isEqualTo(LegalCaseProcessingOutcome.SKIPPED_IN_PROGRESS);
        assertThat(statusHistoryRepository.all()).isEmpty();
        assertThat(statusOf(legalCase)).isEqualTo(LegalCaseStatus.RECEIVED);
    }

    @Test
    @DisplayName("uma tentativa que falhou antes é retomada na entrega seguinte")
    void shouldRetryPreviouslyFailedEvent() {
        LegalCase legalCase = givenLegalCase(LegalCaseStatus.RECEIVED);
        LegalCaseReceivedEvent event = eventFor(legalCase.id());
        processingEventStore.put(event.idempotencyKey(), State.FAILED);

        assertThat(service.process(event).outcome()).isEqualTo(LegalCaseProcessingOutcome.PROCESSED);
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
    @DisplayName("demanda que já passou da extração faz o evento falhar, em vez de forçar a transição")
    void shouldMarkFailedWhenTransitionIsNotAllowed() {
        LegalCase legalCase = givenLegalCase(LegalCaseStatus.PENDING_HUMAN_REVIEW);
        LegalCaseReceivedEvent event = eventFor(legalCase.id());

        assertThatExceptionOfType(InvalidStatusTransitionException.class).isThrownBy(() -> service.process(event));

        assertThat(processingEventStore.stateOf(event.idempotencyKey())).isEqualTo(State.FAILED);
        assertThat(statusOf(legalCase)).isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW);
        assertThat(checklistItemRepository.all()).isEmpty();
        assertThat(extractor.calls()).isEmpty();
    }

    @Test
    @DisplayName("resultado de descarte só aceita desfechos de descarte")
    void shouldRejectSkippedResultWithProcessedOutcome() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> LegalCaseProcessingResult.skipped(LegalCaseProcessingOutcome.PROCESSED));
    }
}
