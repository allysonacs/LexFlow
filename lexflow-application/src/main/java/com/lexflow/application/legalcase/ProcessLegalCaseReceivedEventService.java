package com.lexflow.application.legalcase;

import com.lexflow.application.checklist.DocumentChecklistService;
import com.lexflow.application.document.DocumentRepository;
import com.lexflow.application.document.ExtractDocumentTextService;
import com.lexflow.application.event.ProcessingEventStore;
import com.lexflow.application.event.ProcessingReservation;
import com.lexflow.application.transaction.TransactionRunner;
import com.lexflow.domain.checklist.DocumentChecklist;
import com.lexflow.domain.classification.LegalCaseClassification;
import com.lexflow.domain.classification.LegalCaseKeywordClassifier;
import com.lexflow.domain.document.Document;
import com.lexflow.domain.document.DocumentTextContent;
import com.lexflow.domain.exception.LegalCaseNotFoundException;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Caso de uso disparado pelo consumo de {@link LegalCaseReceivedEvent}: leva a demanda da caixa de
 * entrada até o texto extraído, com o checklist documental gerado, sem nenhuma chamada a LLM.
 *
 * <p>As etapas, na ordem:
 *
 * <ol>
 *   <li><strong>{@code RECEIVED → CLASSIFYING}</strong>: a demanda entra em processamento;
 *   <li><strong>classificação</strong>: o tipo informado é validado contra as palavras-chave dos
 *       nomes de arquivo e da descrição (Prompt 08), e o resultado vai para o histórico;
 *   <li><strong>{@code CLASSIFYING → EXTRACTING}</strong>;
 *   <li><strong>checklist documental</strong> (Prompt 09): os itens das regras do tipo são gerados e
 *       os documentos enviados são vinculados a eles, pelo tipo informado no upload;
 *   <li><strong>extração de texto</strong> de cada documento, nativa ou por OCR.
 * </ol>
 *
 * <p>A demanda <strong>permanece em {@code EXTRACTING}</strong> ao fim: a seção 4 só permite sair
 * dali para {@code AI_ANALYSIS_IN_PROGRESS}, e essa transição pertence à extração de fatos (Prompt
 * 11), que ainda compõe a mesma etapa. O texto gravado é o que ela vai consumir.
 *
 * <p><strong>A ordem das operações não é arbitrária.</strong> A reserva do evento vem antes de
 * qualquer escrita, para que uma entrega duplicada seja descartada sem efeito. As duas transições
 * e o checklist ficam em uma transação só — são instantâneos, e não há por que expor um
 * {@code CLASSIFYING} que ninguém chegaria a observar, nem uma demanda classificada sem checklist. A extração fica fora de qualquer transação,
 * porque o OCR pode levar minutos. A marca de falha também fica fora, já que um rollback apagaria o
 * próprio registro da falha.
 *
 * <p><strong>Retomada.</strong> Cada etapa olha o status atual antes de agir. Uma tentativa que
 * falhou durante a extração volta com a demanda já em {@code EXTRACTING}: a classificação não se
 * repete, o checklist só é sincronizado (nada é recriado) e só os documentos ainda sem texto são lidos.
 */
public class ProcessLegalCaseReceivedEventService {

    /** Motivo gravado no histórico, para que a linha do tempo diga de onde veio a transição. */
    private static final String TRANSITION_REASON = "Processamento iniciado pelo consumo do evento "
            + LegalCaseReceivedEvent.EVENT_TYPE;

    private final LegalCaseRepository legalCaseRepository;
    private final DocumentRepository documentRepository;
    private final LegalCaseStatusHistoryRepository statusHistoryRepository;
    private final LegalCaseStatusTransitionService statusTransitionService;
    private final LegalCaseKeywordClassifier classifier;
    private final DocumentChecklistService checklistService;
    private final ExtractDocumentTextService extractDocumentTextService;
    private final ProcessingEventStore processingEventStore;
    private final TransactionRunner transactionRunner;

    public ProcessLegalCaseReceivedEventService(
            LegalCaseRepository legalCaseRepository,
            DocumentRepository documentRepository,
            LegalCaseStatusHistoryRepository statusHistoryRepository,
            LegalCaseStatusTransitionService statusTransitionService,
            LegalCaseKeywordClassifier classifier,
            DocumentChecklistService checklistService,
            ExtractDocumentTextService extractDocumentTextService,
            ProcessingEventStore processingEventStore,
            TransactionRunner transactionRunner) {
        this.legalCaseRepository = Objects.requireNonNull(legalCaseRepository, "legalCaseRepository não pode ser nulo");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository não pode ser nulo");
        this.statusHistoryRepository =
                Objects.requireNonNull(statusHistoryRepository, "statusHistoryRepository não pode ser nulo");
        this.statusTransitionService =
                Objects.requireNonNull(statusTransitionService, "statusTransitionService não pode ser nulo");
        this.classifier = Objects.requireNonNull(classifier, "classifier não pode ser nulo");
        this.checklistService = Objects.requireNonNull(checklistService, "checklistService não pode ser nulo");
        this.extractDocumentTextService =
                Objects.requireNonNull(extractDocumentTextService, "extractDocumentTextService não pode ser nulo");
        this.processingEventStore = Objects.requireNonNull(processingEventStore, "processingEventStore não pode ser nulo");
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner não pode ser nulo");
    }

    /**
     * Processa o evento uma única vez.
     *
     * @return o desfecho, com a classificação e o texto extraído, para que o consumidor registre o que
     *     aconteceu
     * @throws LegalCaseNotFoundException se o evento apontar para uma demanda inexistente
     * @throws com.lexflow.domain.exception.InvalidStatusTransitionException se a demanda já tiver
     *     passado da extração
     * @throws com.lexflow.application.exception.DocumentStorageException se o storage falhar
     * @throws com.lexflow.application.exception.DocumentTextExtractionException se a extração falhar
     *     por um problema de ambiente
     */
    public LegalCaseProcessingResult process(LegalCaseReceivedEvent event) {
        Objects.requireNonNull(event, "event não pode ser nulo");

        ProcessingReservation reservation = processingEventStore.reserve(
                event.idempotencyKey(), LegalCaseReceivedEvent.EVENT_TYPE, event.legalCaseId());

        switch (reservation) {
            case ALREADY_PROCESSED -> {
                return LegalCaseProcessingResult.skipped(LegalCaseProcessingOutcome.SKIPPED_ALREADY_PROCESSED);
            }
            case IN_PROGRESS_ELSEWHERE -> {
                return LegalCaseProcessingResult.skipped(LegalCaseProcessingOutcome.SKIPPED_IN_PROGRESS);
            }
            case RESERVED -> {
                // segue o fluxo
            }
        }

        try {
            Classified classified = transactionRunner.inTransaction(() -> classify(event.legalCaseId()));
            List<DocumentTextContent> textContents =
                    extractDocumentTextService.extractPending(event.legalCaseId());
            // Todas as etapas são retomáveis: se esta marca falhar, a próxima entrega não refaz nada.
            processingEventStore.markProcessed(event.idempotencyKey());
            return new LegalCaseProcessingResult(
                    LegalCaseProcessingOutcome.PROCESSED,
                    classified.classification(),
                    classified.checklist(),
                    textContents);
        } catch (RuntimeException e) {
            // Sem marcar como processado, a próxima entrega tenta de novo — que é o que o Prompt 07
            // pede. Esgotadas as tentativas, a mensagem vai para a dead-letter.
            processingEventStore.markFailed(event.idempotencyKey());
            throw e;
        }
    }

    /**
     * Leva a demanda até {@code EXTRACTING}, classificando-a no caminho, e sincroniza o checklist.
     *
     * @return a classificação feita agora (nula se a demanda já estava em {@code EXTRACTING}) e o
     *     checklist resultante
     */
    private Classified classify(UUID legalCaseId) {
        LegalCase legalCase = legalCaseRepository
                .findById(legalCaseId)
                .orElseThrow(() -> new LegalCaseNotFoundException(legalCaseId));

        if (legalCase.status() == LegalCaseStatus.EXTRACTING) {
            return new Classified(null, checklistService.synchronize(legalCase));
        }

        List<LegalCaseStatusHistoryEntry> history = new ArrayList<>(2);
        if (legalCase.status() != LegalCaseStatus.CLASSIFYING) {
            // Qualquer status diferente de RECEIVED é recusado aqui pela máquina de estados.
            LegalCaseStatusTransitionResult started = statusTransitionService.transition(
                    legalCase, LegalCaseStatus.CLASSIFYING, LegalCaseStatusHistoryEntry.SYSTEM_ACTOR, TRANSITION_REASON);
            legalCase = started.legalCase();
            history.add(started.historyEntry());
        }

        LegalCaseClassification classification = classifier.classify(legalCase.caseType(), signalsOf(legalCase));

        LegalCaseStatusTransitionResult classified = statusTransitionService.transition(
                legalCase,
                LegalCaseStatus.EXTRACTING,
                LegalCaseStatusHistoryEntry.SYSTEM_ACTOR,
                "Classificação concluída: " + classification.summary());
        history.add(classified.historyEntry());

        legalCaseRepository.save(classified.legalCase());
        history.forEach(statusHistoryRepository::save);
        return new Classified(classification, checklistService.synchronize(classified.legalCase()));
    }

    /** Textos escolhidos pelo requisitante: a descrição e o nome de cada arquivo. */
    private List<String> signalsOf(LegalCase legalCase) {
        List<String> signals = new ArrayList<>();
        if (legalCase.description() != null) {
            signals.add(legalCase.description());
        }
        documentRepository.findByLegalCaseId(legalCase.id()).stream()
                .map(Document::fileName)
                .forEach(signals::add);
        return signals;
    }

    /** Resultado da etapa transacional. */
    private record Classified(LegalCaseClassification classification, DocumentChecklist checklist) {}
}
