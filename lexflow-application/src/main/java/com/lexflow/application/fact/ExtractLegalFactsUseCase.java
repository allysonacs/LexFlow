package com.lexflow.application.fact;

import com.lexflow.application.alert.LegalCaseAlertRepository;
import com.lexflow.application.document.DocumentRepository;
import com.lexflow.application.document.DocumentTextContentRepository;
import com.lexflow.application.legalcase.LegalCaseRepository;
import com.lexflow.application.legalcase.LegalCaseStatusHistoryEntry;
import com.lexflow.application.legalcase.LegalCaseStatusHistoryRepository;
import com.lexflow.application.legalcase.LegalCaseStatusTransitionResult;
import com.lexflow.application.legalcase.LegalCaseStatusTransitionService;
import com.lexflow.application.llm.LlmClientPort;
import com.lexflow.application.llm.LlmRefusalException;
import com.lexflow.application.llm.LlmRequest;
import com.lexflow.application.llm.LlmResponse;
import com.lexflow.application.llm.LlmResponseValidationException;
import com.lexflow.application.llm.StructuredOutputValidator;
import com.lexflow.application.prompt.PromptTemplate;
import com.lexflow.application.prompt.PromptVersionNotFoundException;
import com.lexflow.application.prompt.PromptVersionRepository;
import com.lexflow.application.transaction.TransactionRunner;
import com.lexflow.domain.ai.AiExtractedFact;
import com.lexflow.domain.alert.LegalCaseAlert;
import com.lexflow.domain.alert.LegalCaseAlertType;
import com.lexflow.domain.document.Document;
import com.lexflow.domain.document.DocumentTextContent;
import com.lexflow.domain.exception.InvalidStatusTransitionException;
import com.lexflow.domain.exception.LegalCaseNotFoundException;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Extrai, via LLM, os fatos de cada documento de uma demanda e, se tudo der certo, avança a demanda
 * para {@code AI_ANALYSIS_IN_PROGRESS} (seção 10, item 1; Prompt 11).
 *
 * <p><strong>Só fatos.</strong> O prompt instrui o modelo a registrar apenas o que está escrito, a
 * usar {@code null} para o que não consta e a não emitir opinião jurídica. Nenhuma pergunta jurídica
 * é respondida aqui.
 *
 * <p><strong>Nada inválido é gravado.</strong> A resposta passa por duas barreiras: a do cliente LLM
 * e a deste caso de uso, que confere o JSON contra o mesmo schema antes de persistir. Se falhar, há
 * uma única nova tentativa, com o prompt reforçando o formato e listando as violações. Se falhar de
 * novo, nada é gravado para o documento: a demanda recebe um alerta e não avança sozinha.
 *
 * <p><strong>O que para a demanda e o que sobe como exceção:</strong>
 *
 * <ul>
 *   <li>saída inválida duas vezes, recusa do modelo, documento longo demais: alerta no documento, e os
 *       demais documentos seguem sendo processados;
 *   <li>provedor indisponível ({@code LlmUnavailableException}), pedido recusado pelo provedor
 *       ({@code LlmRequestRejectedException}) ou prompt sem versão ativa: a exceção sobe, e a
 *       mensagem da fila é tentada de novo — são falhas de ambiente, não do documento.
 * </ul>
 *
 * <p><strong>Retomável.</strong> Documentos que já têm fatos ou alerta são pulados, então uma nova
 * tentativa não refaz chamadas já pagas nem repete uma extração que já falhou.
 */
public class ExtractLegalFactsUseCase {

    /** Chave do prompt em {@code prompt_versions}. */
    public static final String PROMPT_KEY = "FACT_EXTRACTION";

    static final String SYSTEM_SECTION = "SISTEMA";
    static final String USER_SECTION = "USUARIO";
    static final String REINFORCEMENT_SECTION = "REFORCO";

    /** Tipos de alerta desta etapa: um documento com algum deles não é tentado de novo automaticamente. */
    private static final Set<LegalCaseAlertType> EXTRACTION_ALERTS = Set.of(
            LegalCaseAlertType.FACT_EXTRACTION_INVALID_OUTPUT,
            LegalCaseAlertType.FACT_EXTRACTION_REFUSED,
            LegalCaseAlertType.DOCUMENT_TOO_LONG_FOR_EXTRACTION);

    private final LegalCaseRepository legalCaseRepository;
    private final DocumentRepository documentRepository;
    private final DocumentTextContentRepository textContentRepository;
    private final AiExtractedFactRepository factRepository;
    private final LegalCaseAlertRepository alertRepository;
    private final PromptVersionRepository promptVersionRepository;
    private final LlmClientPort llmClient;
    private final StructuredOutputValidator validator;
    private final LegalCaseStatusTransitionService statusTransitionService;
    private final LegalCaseStatusHistoryRepository statusHistoryRepository;
    private final TransactionRunner transactionRunner;
    private final Clock clock;
    private final Supplier<UUID> idGenerator;
    private final int maxDocumentCharacters;

    /**
     * @param maxDocumentCharacters acima deste tamanho o documento não é enviado — e nunca é truncado
     *     em silêncio: recebe um alerta para tratamento humano
     */
    public ExtractLegalFactsUseCase(
            LegalCaseRepository legalCaseRepository,
            DocumentRepository documentRepository,
            DocumentTextContentRepository textContentRepository,
            AiExtractedFactRepository factRepository,
            LegalCaseAlertRepository alertRepository,
            PromptVersionRepository promptVersionRepository,
            LlmClientPort llmClient,
            StructuredOutputValidator validator,
            LegalCaseStatusTransitionService statusTransitionService,
            LegalCaseStatusHistoryRepository statusHistoryRepository,
            TransactionRunner transactionRunner,
            Clock clock,
            Supplier<UUID> idGenerator,
            int maxDocumentCharacters) {
        this.legalCaseRepository = Objects.requireNonNull(legalCaseRepository, "legalCaseRepository não pode ser nulo");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository não pode ser nulo");
        this.textContentRepository =
                Objects.requireNonNull(textContentRepository, "textContentRepository não pode ser nulo");
        this.factRepository = Objects.requireNonNull(factRepository, "factRepository não pode ser nulo");
        this.alertRepository = Objects.requireNonNull(alertRepository, "alertRepository não pode ser nulo");
        this.promptVersionRepository =
                Objects.requireNonNull(promptVersionRepository, "promptVersionRepository não pode ser nulo");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient não pode ser nulo");
        this.validator = Objects.requireNonNull(validator, "validator não pode ser nulo");
        this.statusTransitionService =
                Objects.requireNonNull(statusTransitionService, "statusTransitionService não pode ser nulo");
        this.statusHistoryRepository =
                Objects.requireNonNull(statusHistoryRepository, "statusHistoryRepository não pode ser nulo");
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner não pode ser nulo");
        this.clock = Objects.requireNonNull(clock, "clock não pode ser nulo");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator não pode ser nulo");
        if (maxDocumentCharacters < 1) {
            throw new IllegalArgumentException("maxDocumentCharacters deve ser positivo");
        }
        this.maxDocumentCharacters = maxDocumentCharacters;
    }

    /**
     * Extrai os fatos dos documentos com texto que ainda não foram tratados.
     *
     * @throws LegalCaseNotFoundException se a demanda não existir
     * @throws InvalidStatusTransitionException se a demanda não estiver em {@code EXTRACTING} (nem já
     *     em {@code AI_ANALYSIS_IN_PROGRESS}, caso em que a etapa já foi concluída e nada é feito)
     * @throws PromptVersionNotFoundException se não houver versão ativa do prompt
     */
    public FactExtractionResult extract(UUID legalCaseId) {
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        LegalCase legalCase = legalCaseRepository
                .findById(legalCaseId)
                .orElseThrow(() -> new LegalCaseNotFoundException(legalCaseId));

        // Uma demanda que já passou desta etapa não a refaz: a extração está concluída, e uma nova
        // entrega da mensagem apenas segue para a etapa seguinte.
        if (legalCase.status() == LegalCaseStatus.AI_ANALYSIS_IN_PROGRESS
                || legalCase.status() == LegalCaseStatus.PENDING_HUMAN_REVIEW) {
            return new FactExtractionResult(
                    factRepository.findByLegalCaseId(legalCaseId), openAlerts(legalCaseId), 0, false);
        }
        if (legalCase.status() != LegalCaseStatus.EXTRACTING) {
            throw new InvalidStatusTransitionException(legalCase.status(), LegalCaseStatus.AI_ANALYSIS_IN_PROGRESS);
        }

        PromptTemplate template = PromptTemplate.of(promptVersionRepository
                .findActive(PROMPT_KEY)
                .orElseThrow(() -> new PromptVersionNotFoundException(PROMPT_KEY)));
        String schema = FactExtractionSchema.forType(legalCase.caseType());

        Set<UUID> handled = factRepository.findByLegalCaseId(legalCaseId).stream()
                .map(AiExtractedFact::documentId)
                .collect(Collectors.toSet());
        openAlerts(legalCaseId).stream()
                .filter(alert -> EXTRACTION_ALERTS.contains(alert.type()) && alert.documentId() != null)
                .forEach(alert -> handled.add(alert.documentId()));

        Map<UUID, DocumentTextContent> texts = textContentRepository.findByLegalCaseId(legalCaseId).stream()
                .filter(DocumentTextContent::hasText)
                .collect(Collectors.toMap(DocumentTextContent::documentId, Function.identity()));

        int llmCalls = 0;
        for (Document document : documentRepository.findByLegalCaseId(legalCaseId)) {
            DocumentTextContent text = texts.get(document.id());
            if (text == null || handled.contains(document.id())) {
                continue;
            }
            llmCalls += extractDocument(legalCase, document, text, template, schema);
        }

        List<LegalCaseAlert> openAlerts = openAlerts(legalCaseId);
        boolean advanced = openAlerts.stream().noneMatch(LegalCaseAlert::blocksPipeline);
        List<AiExtractedFact> facts = factRepository.findByLegalCaseId(legalCaseId);
        if (advanced) {
            advance(legalCase, facts.size());
        }
        return new FactExtractionResult(facts, openAlerts, llmCalls, advanced);
    }

    /**
     * Extrai e grava os fatos de um documento, ou registra o alerta correspondente.
     *
     * @return quantas chamadas ao LLM foram feitas
     */
    private int extractDocument(
            LegalCase legalCase, Document document, DocumentTextContent text, PromptTemplate template, String schema) {
        if (text.characterCount() > maxDocumentCharacters) {
            openAlert(legalCase, document, LegalCaseAlertType.DOCUMENT_TOO_LONG_FOR_EXTRACTION,
                    "Documento '%s' tem %d caracteres, acima do limite de %d para a extração de fatos; nada foi enviado ao LLM"
                            .formatted(document.fileName(), text.characterCount(), maxDocumentCharacters));
            return 0;
        }

        Map<String, String> values = Map.of(
                "CASE_TYPE", legalCase.caseType().name(),
                "SPECIFIC_FIELDS", FactExtractionSchema.fieldsGuide(legalCase.caseType()),
                "FILE_NAME", sanitizeFileName(document.fileName()),
                "DOCUMENT_TEXT", text.content());
        String system = template.render(SYSTEM_SECTION, values);
        String user = template.render(USER_SECTION, values);

        try {
            Attempt first = attempt(new LlmRequest(system, user, schema, null, null, null, null), schema);
            if (first.isValid()) {
                saveFact(legalCase, document, first, template);
                return 1;
            }

            String reinforcement = template.render(
                    REINFORCEMENT_SECTION, Map.of("VIOLATIONS", bulletList(first.violations())));
            Attempt second = attempt(
                    new LlmRequest(system, user + "\n\n" + reinforcement, schema, null, null, null, null), schema);
            if (second.isValid()) {
                saveFact(legalCase, document, second, template);
                return 2;
            }

            openAlert(legalCase, document, LegalCaseAlertType.FACT_EXTRACTION_INVALID_OUTPUT,
                    "Fatos do documento '%s' fora do formato esperado após 2 tentativas (%s, modelo %s). Violações: %s"
                            .formatted(document.fileName(), template.version().label(), second.model(),
                                    String.join("; ", second.violations())));
            return 2;
        } catch (LlmRefusalException e) {
            openAlert(legalCase, document, LegalCaseAlertType.FACT_EXTRACTION_REFUSED,
                    "O modelo %s se recusou a extrair os fatos do documento '%s' (categoria: %s)"
                            .formatted(e.model(), document.fileName(), e.category()));
            return 1;
        }
    }

    /** Uma chamada, com a validação deste caso de uso somada à do cliente. */
    private Attempt attempt(LlmRequest request, String schema) {
        try {
            LlmResponse response = llmClient.complete(request);
            if (response.structuredOutput() == null) {
                return Attempt.invalid(response.model(), List.of("resposta sem conteúdo estruturado"));
            }
            List<String> violations = validator.violations(schema, response.structuredOutput());
            return violations.isEmpty()
                    ? Attempt.valid(response.model(), response.structuredOutput())
                    : Attempt.invalid(response.model(), violations);
        } catch (LlmResponseValidationException e) {
            List<String> violations = e.violations().isEmpty() ? List.of(e.getMessage()) : e.violations();
            return Attempt.invalid(e.model(), violations);
        }
    }

    private void saveFact(LegalCase legalCase, Document document, Attempt attempt, PromptTemplate template) {
        factRepository.save(new AiExtractedFact(
                idGenerator.get(),
                legalCase.id(),
                document.id(),
                attempt.json(),
                attempt.model(),
                template.version().id(),
                clock.instant()));
    }

    private void openAlert(LegalCase legalCase, Document document, LegalCaseAlertType type, String message) {
        alertRepository.save(LegalCaseAlert.open(
                idGenerator.get(), legalCase.id(), document.id(), type, message, clock.instant()));
    }

    private void advance(LegalCase legalCase, int factCount) {
        transactionRunner.runInTransaction(() -> {
            LegalCaseStatusTransitionResult transition = statusTransitionService.transition(
                    legalCase,
                    LegalCaseStatus.AI_ANALYSIS_IN_PROGRESS,
                    LegalCaseStatusHistoryEntry.SYSTEM_ACTOR,
                    "Fatos extraídos de %d documento(s) pelo LLM".formatted(factCount));
            legalCaseRepository.save(transition.legalCase());
            statusHistoryRepository.save(transition.historyEntry());
        });
    }

    private List<LegalCaseAlert> openAlerts(UUID legalCaseId) {
        return alertRepository.findByLegalCaseId(legalCaseId).stream()
                .filter(LegalCaseAlert::isOpen)
                .toList();
    }

    private static String bulletList(List<String> items) {
        List<String> lines = new ArrayList<>(items.size());
        items.forEach(item -> lines.add("- " + item));
        return String.join("\n", lines);
    }

    /** O nome do arquivo vai dentro de um atributo do prompt: aspas e sinais de tag são removidos. */
    private static String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[\"<>\\r\\n]", "_");
    }

    /** Resultado de uma chamada: JSON válido ou a lista de violações. */
    private record Attempt(String model, String json, List<String> violations) {

        static Attempt valid(String model, String json) {
            return new Attempt(model, json, List.of());
        }

        static Attempt invalid(String model, List<String> violations) {
            return new Attempt(model, null, List.copyOf(violations));
        }

        boolean isValid() {
            return json != null;
        }
    }
}
