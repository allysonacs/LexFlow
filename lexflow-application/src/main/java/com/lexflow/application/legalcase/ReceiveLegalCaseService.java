package com.lexflow.application.legalcase;

import com.lexflow.application.document.DocumentRepository;
import com.lexflow.application.document.DocumentStoragePort;
import com.lexflow.application.document.DocumentUpload;
import com.lexflow.application.exception.IdempotentRequestInProgressException;
import com.lexflow.application.transaction.TransactionRunner;
import com.lexflow.domain.document.Document;
import com.lexflow.domain.document.DocumentFormat;
import com.lexflow.domain.document.Sha256Checksum;
import com.lexflow.domain.exception.LegalCaseNotFoundException;
import com.lexflow.domain.legalcase.LegalCase;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Caso de uso de ingestão: recebe uma demanda jurídica com os seus arquivos e a deixa pronta para o
 * processamento assíncrono.
 *
 * <p><strong>O que este caso de uso não faz:</strong> nenhuma classificação, nenhuma extração e
 * nenhuma chamada de IA. A restrição do Prompt 05 é deliberada — a requisição do cliente não pode
 * ficar presa esperando um modelo responder. Tudo o que depende de processamento acontece depois,
 * disparado pelo evento publicado no fim do fluxo.
 *
 * <p>A ordem das etapas foi escolhida para não deixar rastro inconsistente:
 *
 * <ol>
 *   <li>valida os arquivos antes de qualquer gravação, para que um formato recusado não crie nada;
 *   <li>reserva a chave de idempotência, o que decide cedo se esta requisição cria uma demanda nova
 *       ou apenas repete a resposta de uma anterior;
 *   <li>envia os binários ao storage, que fica fora da transação por ser um recurso externo;
 *   <li>grava demanda, histórico, metadados e a conclusão da chave em uma transação só;
 *   <li>publica o evento, já com tudo confirmado no banco — nunca antes, para que nenhum worker
 *       procure uma demanda que ainda não existe.
 * </ol>
 */
public class ReceiveLegalCaseService {

    /** Autor registrado no histórico: a ingestão parte do sistema, não de uma pessoa. */
    private static final String INITIAL_STATUS_REASON = "Demanda recebida pela API de ingestão";

    private final LegalCaseRepository legalCaseRepository;
    private final DocumentRepository documentRepository;
    private final LegalCaseStatusHistoryRepository statusHistoryRepository;
    private final LegalCaseStatusTransitionService statusTransitionService;
    private final DocumentStoragePort documentStorage;
    private final LegalCaseIngestionIdempotencyStore idempotencyStore;
    private final LegalCaseReceivedEventPublisher eventPublisher;
    private final TransactionRunner transactionRunner;
    private final Clock clock;
    private final Supplier<UUID> idGenerator;

    public ReceiveLegalCaseService(
            LegalCaseRepository legalCaseRepository,
            DocumentRepository documentRepository,
            LegalCaseStatusHistoryRepository statusHistoryRepository,
            LegalCaseStatusTransitionService statusTransitionService,
            DocumentStoragePort documentStorage,
            LegalCaseIngestionIdempotencyStore idempotencyStore,
            LegalCaseReceivedEventPublisher eventPublisher,
            TransactionRunner transactionRunner,
            Clock clock,
            Supplier<UUID> idGenerator) {
        this.legalCaseRepository = Objects.requireNonNull(legalCaseRepository, "legalCaseRepository não pode ser nulo");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository não pode ser nulo");
        this.statusHistoryRepository =
                Objects.requireNonNull(statusHistoryRepository, "statusHistoryRepository não pode ser nulo");
        this.statusTransitionService =
                Objects.requireNonNull(statusTransitionService, "statusTransitionService não pode ser nulo");
        this.documentStorage = Objects.requireNonNull(documentStorage, "documentStorage não pode ser nulo");
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore não pode ser nulo");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher não pode ser nulo");
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner não pode ser nulo");
        this.clock = Objects.requireNonNull(clock, "clock não pode ser nulo");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator não pode ser nulo");
    }

    /**
     * Recebe uma demanda.
     *
     * @throws com.lexflow.domain.exception.UnsupportedDocumentFormatException se algum arquivo não
     *     estiver em um formato aceito
     * @throws IdempotentRequestInProgressException se a chave de idempotência já estiver reservada por
     *     uma ingestão que ainda não terminou
     */
    public ReceiveLegalCaseResult receive(ReceiveLegalCaseCommand command) {
        Objects.requireNonNull(command, "command não pode ser nulo");

        // Valida tudo antes de reservar chave ou gravar qualquer coisa: um arquivo recusado não pode
        // deixar para trás uma chave de idempotência queimada.
        List<DocumentFormat> formats = command.documents().stream()
                .map(DocumentUpload::resolveFormat)
                .toList();

        UUID legalCaseId = idGenerator.get();

        if (command.hasIdempotencyKey()) {
            Optional<IdempotentIngestion> existing = idempotencyStore.reserve(command.idempotencyKey(), legalCaseId);
            if (existing.isPresent()) {
                return replay(existing.get());
            }
        }

        Instant receivedAt = clock.instant();
        LegalCase legalCase = LegalCase.receive(
                legalCaseId,
                command.externalReference(),
                command.caseType(),
                command.requester(),
                command.priority(),
                receivedAt);

        // A demanda já nasce em RECEIVED, mas o registro inicial passa pelo serviço de transição para
        // que nem o primeiro estado fique de fora da linha do tempo (seção 4).
        LegalCaseStatusTransitionResult transition = statusTransitionService.registerInitialStatus(
                legalCase, LegalCaseStatusHistoryEntry.SYSTEM_ACTOR, INITIAL_STATUS_REASON);

        List<Document> documents = storeDocuments(legalCaseId, command.documents(), formats, receivedAt);

        transactionRunner.runInTransaction(() -> {
            legalCaseRepository.save(transition.legalCase());
            statusHistoryRepository.save(transition.historyEntry());
            documentRepository.saveAll(documents);
            if (command.hasIdempotencyKey()) {
                idempotencyStore.markCompleted(command.idempotencyKey());
            }
        });

        eventPublisher.publish(LegalCaseReceivedEvent.of(
                idGenerator.get(),
                legalCaseId,
                command.caseType(),
                command.priority(),
                documents.size(),
                clock.instant()));

        return new ReceiveLegalCaseResult(new LegalCaseWithDocuments(transition.legalCase(), documents), false);
    }

    /**
     * Devolve a demanda criada pela primeira requisição que usou esta chave.
     *
     * <p>Os arquivos reenviados são descartados de propósito: a promessa da idempotência é que a
     * segunda chamada não produz efeito nenhum, e não que ela acrescenta documentos à demanda
     * original.
     */
    private ReceiveLegalCaseResult replay(IdempotentIngestion ingestion) {
        if (!ingestion.completed()) {
            throw new IdempotentRequestInProgressException(ingestion.idempotencyKey());
        }
        LegalCase legalCase = legalCaseRepository
                .findById(ingestion.legalCaseId())
                .orElseThrow(() -> new LegalCaseNotFoundException(ingestion.legalCaseId()));
        List<Document> documents = documentRepository.findByLegalCaseId(ingestion.legalCaseId());
        return new ReceiveLegalCaseResult(new LegalCaseWithDocuments(legalCase, documents), true);
    }

    /**
     * Envia cada binário ao storage e monta os metadados correspondentes.
     *
     * <p>O checksum é calculado aqui, sobre o conteúdo recebido, e não delegado ao storage: é ele que
     * sustenta a detecção de reenvio do mesmo arquivo, e essa garantia não pode depender do adapter
     * que estiver em uso. Arquivos idênticos dentro da mesma requisição são gravados uma vez só.
     */
    private List<Document> storeDocuments(
            UUID legalCaseId, List<DocumentUpload> uploads, List<DocumentFormat> formats, Instant uploadedAt) {
        List<Document> documents = new ArrayList<>(uploads.size());
        Set<Sha256Checksum> alreadyStored = new HashSet<>();
        for (int index = 0; index < uploads.size(); index++) {
            DocumentUpload upload = uploads.get(index);
            Sha256Checksum checksum = Sha256Checksum.ofContent(upload.content());
            // O mesmo arquivo enviado duas vezes na mesma requisição é um documento só: o banco tem
            // índice único em (legal_case_id, checksum_sha256) e recusaria a segunda linha.
            if (!alreadyStored.add(checksum)) {
                continue;
            }
            UUID documentId = idGenerator.get();
            String storagePath = documentStorage.store(legalCaseId, documentId, upload);
            documents.add(new Document(
                    documentId,
                    legalCaseId,
                    upload.fileName(),
                    storagePath,
                    // Grava o tipo canônico do formato, e não o que o cliente declarou: assim um
                    // "application/octet-stream" genérico não chega ao banco.
                    formats.get(index).canonicalMimeType(),
                    checksum,
                    uploadedAt));
        }
        return List.copyOf(documents);
    }
}
