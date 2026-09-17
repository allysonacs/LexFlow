package com.lexflow.application.legalcase;

import com.lexflow.application.analysis.AnalyzeLegalCaseUseCase;
import com.lexflow.application.analysis.LegalCaseAnalysisResult;
import com.lexflow.application.checklist.DocumentChecklistService;
import com.lexflow.application.document.DocumentRepository;
import com.lexflow.application.document.ExtractDocumentTextService;
import com.lexflow.application.event.ProcessingEventStore;
import com.lexflow.application.fact.ExtractLegalFactsUseCase;
import com.lexflow.application.fact.FactExtractionResult;
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
 * entrada até os fatos extraídos, pronta para a análise da IA.
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
 *   <li><strong>extração de texto</strong> de cada documento, nativa ou por OCR;
 *   <li><strong>extração de fatos</strong> via LLM (Prompt 11), que leva a demanda a
 *       {@code AI_ANALYSIS_IN_PROGRESS} — ou a deixa em {@code EXTRACTING}, com alerta, quando algum
 *       documento precisa de atenção humana;
 *   <li><strong>análise jurídica</strong> (Prompt 13): cada pergunta aplicável ao tipo da demanda é
 *       respondida com apoio da base normativa, e a demanda segue para {@code PENDING_HUMAN_REVIEW}.
 * </ol>
 *
 * <p>As duas etapas de IA podem ser desligadas por configuração: sem a extração de fatos a demanda
 * termina em {@code EXTRACTING}; sem a análise, em {@code AI_ANALYSIS_IN_PROGRESS}.
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
 * repete, o checklist só é sincronizado (nada é recriado), só os documentos ainda sem texto são lidos e
 * só os documentos ainda sem fatos vão para o LLM. Uma demanda que já chegou a
 * {@code AI_ANALYSIS_IN_PROGRESS} — a marca de "processado" falhou depois da transição — é tratada
 * como concluída.
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
    private final ExtractLegalFactsUseCase extractLegalFactsUseCase;
    private final boolean factExtractionEnabled;
    private final AnalyzeLegalCaseUseCase analyzeLegalCaseUseCase;
    private final boolean legalAnalysisEnabled;
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
            ExtractLegalFactsUseCase extractLegalFactsUseCase,
            boolean factExtractionEnabled,
            AnalyzeLegalCaseUseCase analyzeLegalCaseUseCase,
            boolean legalAnalysisEnabled,
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
        this.extractLegalFactsUseCase =
                Objects.requireNonNull(extractLegalFactsUseCase, "extractLegalFactsUseCase não pode ser nulo");
        this.factExtractionEnabled = factExtractionEnabled;
        this.analyzeLegalCaseUseCase =
                Objects.requireNonNull(analyzeLegalCaseUseCase, "analyzeLegalCaseUseCase não pode ser nulo");
        this.legalAnalysisEnabled = legalAnalysisEnabled;
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
     * @throws com.lexflow.application.llm.LlmUnavailableException se o provedor de LLM estiver
     *     indisponível
     * @throws com.lexflow.application.llm.LlmRequestRejectedException se o provedor recusar o pedido
     *     (configuração)
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
            FactExtractionResult facts =
                    factExtractionEnabled ? extractLegalFactsUseCase.extract(event.legalCaseId()) : null;
            // A análise só roda quando nada ficou pendente na extração: com um documento em alerta, a
            // demanda continua em EXTRACTING e não há o que analisar ainda. A condição olha os alertas,
            // e não o "avançou agora", para que uma nova tentativa retome a análise de onde parou.
            LegalCaseAnalysisResult analysis = legalAnalysisEnabled && facts != null && facts.openAlerts().isEmpty()
                    ? analyzeLegalCaseUseCase.analyze(event.legalCaseId())
                    : null;
            // Todas as etapas são retomáveis: se esta marca falhar, a próxima entrega não refaz nada.
            processingEventStore.markProcessed(event.idempotencyKey());
            return new LegalCaseProcessingResult(
                    LegalCaseProcessingOutcome.PROCESSED,
                    classified.classification(),
                    classified.checklist(),
                    textContents,
                    facts,
                    analysis);
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

        if (legalCase.status() == LegalCaseStatus.EXTRACTING
                || legalCase.status() == LegalCaseStatus.AI_ANALYSIS_IN_PROGRESS) {
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
