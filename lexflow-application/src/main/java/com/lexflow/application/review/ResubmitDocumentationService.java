package com.lexflow.application.review;

import com.lexflow.application.alert.LegalCaseAlertRepository;
import com.lexflow.application.analysis.AiAnalysisResponseRepository;
import com.lexflow.application.document.DocumentRepository;
import com.lexflow.application.document.DocumentUpload;
import com.lexflow.application.document.StoreDocumentsService;
import com.lexflow.application.legalcase.LegalCaseReceivedEvent;
import com.lexflow.application.legalcase.LegalCaseReceivedEventPublisher;
import com.lexflow.application.legalcase.LegalCaseRepository;
import com.lexflow.application.legalcase.LegalCaseStatusHistoryRepository;
import com.lexflow.application.legalcase.LegalCaseStatusTransitionResult;
import com.lexflow.application.legalcase.LegalCaseStatusTransitionService;
import com.lexflow.application.legalcase.LegalCaseWithDocuments;
import com.lexflow.application.transaction.TransactionRunner;
import com.lexflow.domain.alert.LegalCaseAlert;
import com.lexflow.domain.document.Document;
import com.lexflow.domain.document.DocumentFormat;
import com.lexflow.domain.document.Sha256Checksum;
import com.lexflow.domain.exception.LegalCaseNotFoundException;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Reabre uma demanda devolvida para correção quando a documentação que faltava é enviada
 * (Prompt 15, item 4).
 *
 * <p>A máquina de estados já previa o caminho {@code RETURNED_FOR_CORRECTION → RECEIVED} (seção 4);
 * o que este caso de uso faz é dar a ele um gatilho concreto — e deixar a demanda em condição de ser
 * analisada de novo, e não apenas mudar de status:
 *
 * <ul>
 *   <li><strong>as respostas anteriores da IA são descartadas.</strong> Elas foram dadas sobre uma
 *       documentação que mudou; mantê-las faria o revisor ler uma análise que não corresponde mais
 *       ao que está anexado;
 *   <li><strong>os alertas em aberto são resolvidos.</strong> Eles apontavam o que faltava ou o que
 *       não pôde ser lido, e é exatamente isso que o reenvio responde. Sem resolvê-los, o pipeline
 *       pararia de novo no mesmo ponto;
 *   <li><strong>o evento de demanda recebida é republicado</strong>, com um novo identificador: o
 *       pipeline recomeça da classificação, e a chave de idempotência derivada do evento garante que
 *       este reprocessamento não se confunda com o anterior.
 * </ul>
 *
 * <p>Os documentos já anexados permanecem: o reenvio acrescenta o que faltava, não substitui o que
 * já havia sido entregue. Um arquivo idêntico a um já anexado é ignorado, como na ingestão.
 */
public class ResubmitDocumentationService {

    private final LegalCaseRepository legalCaseRepository;
    private final DocumentRepository documentRepository;
    private final AiAnalysisResponseRepository responseRepository;
    private final LegalCaseAlertRepository alertRepository;
    private final LegalCaseStatusTransitionService statusTransitionService;
    private final LegalCaseStatusHistoryRepository statusHistoryRepository;
    private final StoreDocumentsService storeDocumentsService;
    private final LegalCaseReceivedEventPublisher eventPublisher;
    private final TransactionRunner transactionRunner;
    private final Clock clock;
    private final Supplier<UUID> idGenerator;

    public ResubmitDocumentationService(
            LegalCaseRepository legalCaseRepository,
            DocumentRepository documentRepository,
            AiAnalysisResponseRepository responseRepository,
            LegalCaseAlertRepository alertRepository,
            LegalCaseStatusTransitionService statusTransitionService,
            LegalCaseStatusHistoryRepository statusHistoryRepository,
            StoreDocumentsService storeDocumentsService,
            LegalCaseReceivedEventPublisher eventPublisher,
            TransactionRunner transactionRunner,
            Clock clock,
            Supplier<UUID> idGenerator) {
        this.legalCaseRepository = Objects.requireNonNull(legalCaseRepository, "legalCaseRepository não pode ser nulo");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository não pode ser nulo");
        this.responseRepository = Objects.requireNonNull(responseRepository, "responseRepository não pode ser nulo");
        this.alertRepository = Objects.requireNonNull(alertRepository, "alertRepository não pode ser nulo");
        this.statusTransitionService =
                Objects.requireNonNull(statusTransitionService, "statusTransitionService não pode ser nulo");
        this.statusHistoryRepository =
                Objects.requireNonNull(statusHistoryRepository, "statusHistoryRepository não pode ser nulo");
        this.storeDocumentsService =
                Objects.requireNonNull(storeDocumentsService, "storeDocumentsService não pode ser nulo");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher não pode ser nulo");
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner não pode ser nulo");
        this.clock = Objects.requireNonNull(clock, "clock não pode ser nulo");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator não pode ser nulo");
    }

    /**
     * Anexa a documentação reenviada e devolve a demanda ao início do pipeline.
     *
     * @param resubmittedBy quem reenviou, registrado no histórico
     * @throws LegalCaseNotFoundException se a demanda não existir
     * @throws com.lexflow.domain.exception.InvalidStatusTransitionException se a demanda não estiver
     *     devolvida para correção
     * @throws com.lexflow.domain.exception.UnsupportedDocumentFormatException se algum arquivo não
     *     estiver em um formato aceito
     */
    public LegalCaseWithDocuments resubmit(UUID legalCaseId, List<DocumentUpload> uploads, String resubmittedBy) {
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        if (uploads == null || uploads.isEmpty()) {
            throw new IllegalArgumentException("é obrigatório enviar ao menos um arquivo");
        }
        if (resubmittedBy == null || resubmittedBy.isBlank()) {
            throw new IllegalArgumentException("resubmittedBy é obrigatório: o reenvio tem um responsável");
        }
        LegalCase legalCase = legalCaseRepository
                .findById(legalCaseId)
                .orElseThrow(() -> new LegalCaseNotFoundException(legalCaseId));

        // Os formatos são validados antes de qualquer gravação, como na ingestão.
        List<DocumentFormat> formats = storeDocumentsService.resolveFormats(uploads);
        Set<Sha256Checksum> alreadyAttached = documentRepository.findByLegalCaseId(legalCaseId).stream()
                .map(Document::checksum)
                .collect(Collectors.toSet());

        Instant now = clock.instant();
        LegalCaseStatusTransitionResult transition = statusTransitionService.transition(
                legalCase,
                LegalCaseStatus.RECEIVED,
                resubmittedBy,
                "Documentação reenviada: %d arquivo(s)".formatted(uploads.size()));

        List<Document> newDocuments =
                storeDocumentsService.store(legalCaseId, uploads, formats, alreadyAttached, now);

        transactionRunner.runInTransaction(() -> {
            documentRepository.saveAll(newDocuments);
            responseRepository.deleteByLegalCaseId(legalCaseId);
            alertRepository.findByLegalCaseId(legalCaseId).stream()
                    .filter(LegalCaseAlert::isOpen)
                    .map(alert -> new LegalCaseAlert(
                            alert.id(),
                            alert.legalCaseId(),
                            alert.documentId(),
                            alert.type(),
                            alert.message(),
                            alert.createdAt(),
                            now))
                    .forEach(alertRepository::save);
            legalCaseRepository.save(transition.legalCase());
            statusHistoryRepository.save(transition.historyEntry());
        });

        List<Document> allDocuments = documentRepository.findByLegalCaseId(legalCaseId);
        eventPublisher.publish(LegalCaseReceivedEvent.of(
                idGenerator.get(),
                legalCaseId,
                legalCase.caseType(),
                legalCase.priority(),
                allDocuments.size(),
                clock.instant()));

        return new LegalCaseWithDocuments(transition.legalCase(), allDocuments);
    }
}
