package com.lexflow.application.legalcase.support;

import com.lexflow.application.document.DocumentRepository;
import com.lexflow.application.document.DocumentStoragePort;
import com.lexflow.application.document.DocumentUpload;
import com.lexflow.application.legalcase.IdempotentIngestion;
import com.lexflow.application.legalcase.LegalCaseIngestionIdempotencyStore;
import com.lexflow.application.legalcase.LegalCaseReceivedEvent;
import com.lexflow.application.legalcase.LegalCaseReceivedEventPublisher;
import com.lexflow.application.legalcase.LegalCaseRepository;
import com.lexflow.application.legalcase.LegalCaseStatusHistoryEntry;
import com.lexflow.application.legalcase.LegalCaseStatusHistoryRepository;
import com.lexflow.application.transaction.TransactionRunner;
import com.lexflow.domain.document.Document;
import com.lexflow.domain.legalcase.LegalCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Implementações em memória das portas usadas pela ingestão.
 *
 * <p>Ficam aqui, e não em um framework de mocks, porque estes dublês guardam estado — a idempotência
 * só pode ser verificada se a segunda chamada enxergar o que a primeira gravou.
 */
public final class IngestionTestDoubles {

    private IngestionTestDoubles() {
        // classe utilitária
    }

    /** Repositório de demandas em memória. */
    public static final class InMemoryLegalCaseRepository implements LegalCaseRepository {

        private final Map<UUID, LegalCase> storage = new LinkedHashMap<>();

        @Override
        public LegalCase save(LegalCase legalCase) {
            storage.put(legalCase.id(), legalCase);
            return legalCase;
        }

        @Override
        public Optional<LegalCase> findById(UUID id) {
            return Optional.ofNullable(storage.get(id));
        }

        public int count() {
            return storage.size();
        }
    }

    /** Repositório de metadados de documentos em memória. */
    public static final class InMemoryDocumentRepository implements DocumentRepository {

        private final List<Document> storage = new ArrayList<>();

        @Override
        public void saveAll(List<Document> documents) {
            storage.addAll(documents);
        }

        @Override
        public List<Document> findByLegalCaseId(UUID legalCaseId) {
            return storage.stream()
                    .filter(document -> document.legalCaseId().equals(legalCaseId))
                    .toList();
        }

        public List<Document> all() {
            return List.copyOf(storage);
        }
    }

    /** Histórico de status em memória. */
    public static final class InMemoryStatusHistoryRepository implements LegalCaseStatusHistoryRepository {

        private final List<LegalCaseStatusHistoryEntry> entries = new ArrayList<>();

        @Override
        public void save(LegalCaseStatusHistoryEntry entry) {
            entries.add(entry);
        }

        @Override
        public List<LegalCaseStatusHistoryEntry> findByLegalCaseId(UUID legalCaseId) {
            return entries.stream()
                    .filter(entry -> entry.legalCaseId().equals(legalCaseId))
                    .toList();
        }

        public List<LegalCaseStatusHistoryEntry> all() {
            return List.copyOf(entries);
        }
    }

    /** Storage que apenas devolve um caminho previsível e anota o que recebeu. */
    public static final class RecordingDocumentStorage implements DocumentStoragePort {

        private final List<String> storedFileNames = new ArrayList<>();

        @Override
        public String store(UUID legalCaseId, UUID documentId, DocumentUpload upload) {
            storedFileNames.add(upload.fileName());
            return "legal-cases/%s/documents/%s".formatted(legalCaseId, documentId);
        }

        public List<String> storedFileNames() {
            return List.copyOf(storedFileNames);
        }
    }

    /** Controle de idempotência em memória, com a mesma semântica do índice único do banco. */
    public static final class InMemoryIdempotencyStore implements LegalCaseIngestionIdempotencyStore {

        private final Map<String, IdempotentIngestion> reservations = new LinkedHashMap<>();

        @Override
        public Optional<IdempotentIngestion> reserve(String idempotencyKey, UUID legalCaseId) {
            IdempotentIngestion existing = reservations.get(idempotencyKey);
            if (existing != null) {
                return Optional.of(existing);
            }
            reservations.put(idempotencyKey, new IdempotentIngestion(idempotencyKey, legalCaseId, false));
            return Optional.empty();
        }

        @Override
        public void markCompleted(String idempotencyKey) {
            IdempotentIngestion reserved = reservations.get(idempotencyKey);
            if (reserved == null) {
                throw new IllegalStateException("chave não reservada: " + idempotencyKey);
            }
            reservations.put(idempotencyKey, new IdempotentIngestion(idempotencyKey, reserved.legalCaseId(), true));
        }

        public int size() {
            return reservations.size();
        }
    }

    /** Publicador que guarda os eventos, para que o teste confira o que seria enviado à fila. */
    public static final class RecordingEventPublisher implements LegalCaseReceivedEventPublisher {

        private final List<LegalCaseReceivedEvent> published = new ArrayList<>();

        @Override
        public void publish(LegalCaseReceivedEvent event) {
            published.add(event);
        }

        public List<LegalCaseReceivedEvent> published() {
            return List.copyOf(published);
        }
    }

    /** Executa a ação direto, sem transação: aqui interessa o fluxo, não o isolamento. */
    public static final class DirectTransactionRunner implements TransactionRunner {

        private int transactionCount;

        @Override
        public <T> T inTransaction(Supplier<T> action) {
            transactionCount++;
            return action.get();
        }

        @Override
        public void runInTransaction(Runnable action) {
            transactionCount++;
            action.run();
        }

        public int transactionCount() {
            return transactionCount;
        }
    }

    /** Gerador de identificadores sequencial e previsível, para asserções estáveis. */
    public static final class SequentialIdGenerator implements Supplier<UUID> {

        private int next;

        @Override
        public UUID get() {
            return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(++next));
        }
    }
}
