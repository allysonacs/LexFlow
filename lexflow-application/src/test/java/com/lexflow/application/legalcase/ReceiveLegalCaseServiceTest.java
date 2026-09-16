package com.lexflow.application.legalcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.lexflow.application.document.DocumentUpload;
import com.lexflow.application.exception.IdempotentRequestInProgressException;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.DirectTransactionRunner;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryDocumentRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryIdempotencyStore;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryLegalCaseRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryStatusHistoryRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.RecordingDocumentStorage;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.RecordingEventPublisher;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.SequentialIdGenerator;
import com.lexflow.domain.document.Sha256Checksum;
import com.lexflow.domain.exception.UnsupportedDocumentFormatException;
import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Cobre o caso de uso de ingestão do Prompt 05, sem banco, fila nem HTTP. */
class ReceiveLegalCaseServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final String REQUESTER = "ana.silva";

    private InMemoryLegalCaseRepository legalCaseRepository;
    private InMemoryDocumentRepository documentRepository;
    private InMemoryStatusHistoryRepository statusHistoryRepository;
    private RecordingDocumentStorage documentStorage;
    private InMemoryIdempotencyStore idempotencyStore;
    private RecordingEventPublisher eventPublisher;
    private DirectTransactionRunner transactionRunner;
    private ReceiveLegalCaseService service;

    @BeforeEach
    void setUp() {
        legalCaseRepository = new InMemoryLegalCaseRepository();
        documentRepository = new InMemoryDocumentRepository();
        statusHistoryRepository = new InMemoryStatusHistoryRepository();
        documentStorage = new RecordingDocumentStorage();
        idempotencyStore = new InMemoryIdempotencyStore();
        eventPublisher = new RecordingEventPublisher();
        transactionRunner = new DirectTransactionRunner();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ReceiveLegalCaseService(
                legalCaseRepository,
                documentRepository,
                statusHistoryRepository,
                new LegalCaseStatusTransitionService(
                        new com.lexflow.domain.legalcase.LegalCaseStatusTransitionRules(),
                        clock,
                        new SequentialIdGenerator()),
                documentStorage,
                idempotencyStore,
                eventPublisher,
                transactionRunner,
                clock,
                new SequentialIdGenerator());
    }

    private static DocumentUpload upload(String fileName, String content) {
        return new DocumentUpload(fileName, null, content.getBytes(StandardCharsets.UTF_8));
    }

    private ReceiveLegalCaseCommand command(String idempotencyKey, DocumentUpload... uploads) {
        return new ReceiveLegalCaseCommand(
                "REF-2026-001",
                LegalCaseType.CONTRACT_SIGNING,
                REQUESTER,
                "Minuta de contrato de licenciamento",
                CasePriority.HIGH,
                List.of(uploads),
                idempotencyKey);
    }

    @Test
    @DisplayName("a demanda nasce em RECEIVED, com histórico inicial e metadados dos arquivos")
    void shouldReceiveLegalCase() {
        ReceiveLegalCaseResult result = service.receive(command(null, upload("contrato.pdf", "conteúdo")));

        assertThat(result.replayed()).isFalse();
        assertThat(result.legalCase().legalCase().status()).isEqualTo(LegalCaseStatus.RECEIVED);
        assertThat(result.legalCase().legalCase().caseType()).isEqualTo(LegalCaseType.CONTRACT_SIGNING);
        assertThat(result.legalCase().legalCase().description()).isEqualTo("Minuta de contrato de licenciamento");
        assertThat(result.legalCase().legalCase().priority()).isEqualTo(CasePriority.HIGH);
        assertThat(result.legalCase().legalCase().createdAt()).isEqualTo(NOW);
        assertThat(legalCaseRepository.count()).isEqualTo(1);

        assertThat(statusHistoryRepository.all()).singleElement().satisfies(entry -> {
            assertThat(entry.isInitial()).isTrue();
            assertThat(entry.newStatus()).isEqualTo(LegalCaseStatus.RECEIVED);
            assertThat(entry.changedBy()).isEqualTo(LegalCaseStatusHistoryEntry.SYSTEM_ACTOR);
        });

        assertThat(documentRepository.all()).singleElement().satisfies(document -> {
            assertThat(document.fileName()).isEqualTo("contrato.pdf");
            assertThat(document.mimeType()).isEqualTo("application/pdf");
            assertThat(document.checksum())
                    .isEqualTo(Sha256Checksum.ofContent("conteúdo".getBytes(StandardCharsets.UTF_8)));
            assertThat(document.storagePath()).isNotBlank();
        });
    }

    @Test
    @DisplayName("demanda, histórico e documentos são gravados em uma transação só")
    void shouldPersistEverythingInOneTransaction() {
        service.receive(command(null, upload("contrato.pdf", "a"), upload("procuracao.pdf", "b")));

        assertThat(transactionRunner.transactionCount()).isEqualTo(1);
        assertThat(documentStorage.storedFileNames()).containsExactly("contrato.pdf", "procuracao.pdf");
    }

    @Test
    @DisplayName("o evento é publicado depois da gravação, sem nenhuma chamada síncrona de IA")
    void shouldPublishEventAfterPersisting() {
        ReceiveLegalCaseResult result = service.receive(command(null, upload("contrato.pdf", "a")));

        assertThat(eventPublisher.published()).singleElement().satisfies(event -> {
            assertThat(event.legalCaseId()).isEqualTo(result.legalCase().legalCase().id());
            assertThat(event.caseType()).isEqualTo(LegalCaseType.CONTRACT_SIGNING);
            assertThat(event.documentCount()).isEqualTo(1);
            assertThat(event.idempotencyKey()).startsWith(LegalCaseReceivedEvent.EVENT_TYPE);
        });
    }

    @Test
    @DisplayName("reenvio com a mesma Idempotency-Key devolve a demanda original, sem duplicar")
    void shouldNotDuplicateOnSameIdempotencyKey() {
        ReceiveLegalCaseResult first = service.receive(command("chave-1", upload("contrato.pdf", "a")));
        ReceiveLegalCaseResult second = service.receive(command("chave-1", upload("contrato.pdf", "a")));

        assertThat(second.replayed()).isTrue();
        assertThat(second.legalCase().legalCase().id())
                .isEqualTo(first.legalCase().legalCase().id());
        assertThat(legalCaseRepository.count()).isEqualTo(1);
        assertThat(documentRepository.all()).hasSize(1);
        assertThat(statusHistoryRepository.all()).hasSize(1);
        // A segunda requisição não gera evento: nada aconteceu que precise ser processado de novo.
        assertThat(eventPublisher.published()).hasSize(1);
        assertThat(idempotencyStore.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("chaves diferentes criam demandas diferentes")
    void shouldCreateOneCasePerIdempotencyKey() {
        service.receive(command("chave-1", upload("contrato.pdf", "a")));
        service.receive(command("chave-2", upload("contrato.pdf", "a")));

        assertThat(legalCaseRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("sem chave de idempotência, cada requisição cria uma demanda nova")
    void shouldCreateOneCasePerRequestWithoutKey() {
        service.receive(command(null, upload("contrato.pdf", "a")));
        service.receive(command(null, upload("contrato.pdf", "a")));

        assertThat(legalCaseRepository.count()).isEqualTo(2);
        assertThat(idempotencyStore.size()).isZero();
    }

    @Test
    @DisplayName("chave reservada por uma ingestão que não terminou resulta em conflito")
    void shouldRejectKeyReservedByUnfinishedIngestion() {
        idempotencyStore.reserve("chave-presa", java.util.UUID.randomUUID());

        assertThatExceptionOfType(IdempotentRequestInProgressException.class)
                .isThrownBy(() -> service.receive(command("chave-presa", upload("contrato.pdf", "a"))));
        assertThat(legalCaseRepository.count()).isZero();
    }

    @Test
    @DisplayName("arquivo em formato não aceito é recusado antes de qualquer gravação")
    void shouldRejectUnsupportedFormatBeforePersisting() {
        assertThatExceptionOfType(UnsupportedDocumentFormatException.class)
                .isThrownBy(() -> service.receive(command("chave-1", upload("planilha.xlsx", "a"))));

        assertThat(legalCaseRepository.count()).isZero();
        assertThat(documentStorage.storedFileNames()).isEmpty();
        assertThat(eventPublisher.published()).isEmpty();
        // A chave não foi queimada: o cliente pode corrigir o arquivo e reenviar com a mesma chave.
        assertThat(idempotencyStore.size()).isZero();
    }

    @Test
    @DisplayName("mime type que contradiz a extensão é recusado")
    void shouldRejectMismatchingMimeType() {
        DocumentUpload disguised = new DocumentUpload("contrato.pdf", "image/png", "a".getBytes(StandardCharsets.UTF_8));

        assertThatExceptionOfType(UnsupportedDocumentFormatException.class)
                .isThrownBy(() -> service.receive(command(null, disguised)));
    }

    @Test
    @DisplayName("o mesmo arquivo repetido na requisição vira um documento só")
    void shouldDeduplicateIdenticalUploads() {
        service.receive(command(null, upload("contrato.pdf", "mesmo"), upload("copia.pdf", "mesmo")));

        assertThat(documentRepository.all()).hasSize(1);
    }

    @Test
    @DisplayName("uma requisição sem arquivos é recusada")
    void shouldRejectCommandWithoutFiles() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReceiveLegalCaseCommand(
                        null, LegalCaseType.CONTRACT_SIGNING, REQUESTER, null, null, List.of(), null))
                .withMessageContaining("ao menos um arquivo");
    }

    @Test
    @DisplayName("prioridade ausente vira o valor padrão, e não um erro")
    void shouldApplyDefaultPriority() {
        ReceiveLegalCaseCommand command = new ReceiveLegalCaseCommand(
                "  ", LegalCaseType.SUPPLIER_HIRING, REQUESTER, " ", null, List.of(upload("nota.png", "a")), "  ");

        assertThat(command.priority()).isEqualTo(CasePriority.DEFAULT);
        assertThat(command.externalReference()).isNull();
        assertThat(command.description()).isNull();
        assertThat(command.hasIdempotencyKey()).isFalse();
    }
}
