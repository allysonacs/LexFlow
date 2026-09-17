package com.lexflow.application.analysis;

import com.lexflow.application.alert.LegalCaseAlertRepository;
import com.lexflow.application.checklist.DocumentChecklistService;
import com.lexflow.application.checklist.LegalCaseChecklist;
import com.lexflow.application.document.DocumentRepository;
import com.lexflow.application.fact.AiExtractedFactRepository;
import com.lexflow.application.knowledge.KnowledgeBaseRetriever;
import com.lexflow.application.knowledge.RetrievedChunk;
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
import com.lexflow.domain.ai.AiAnalysisResponse;
import com.lexflow.domain.ai.AiExtractedFact;
import com.lexflow.domain.ai.ConfidenceScore;
import com.lexflow.domain.ai.QuestionKey;
import com.lexflow.domain.alert.LegalCaseAlert;
import com.lexflow.domain.alert.LegalCaseAlertType;
import com.lexflow.domain.document.Document;
import com.lexflow.domain.exception.InvalidStatusTransitionException;
import com.lexflow.domain.exception.LegalCaseNotFoundException;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Responde, de forma estruturada e rastreável, cada pergunta jurídica aplicável ao tipo da demanda
 * (Prompt 13). É onde a extração de fatos (Prompt 11) e a base normativa (Prompt 12) se encontram.
 *
 * <p><strong>Uma pergunta de cada vez.</strong> Nunca há uma chamada monolítica que responda tudo: a
 * cada pergunta correspondem uma recuperação normativa própria e uma chamada própria ao modelo
 * (seção 10). Perguntas diferentes se apoiam em normas diferentes, e uma resposta errada não
 * contamina as outras.
 *
 * <p><strong>O que não é perguntado ao modelo.</strong> Duas situações são resolvidas por código e
 * gravadas como {@link com.lexflow.domain.ai.AnswerSource#DETERMINISTIC}:
 *
 * <ul>
 *   <li>documentação incompleta segundo o checklist determinístico (seção 9): perguntar ao modelo se
 *       a documentação é suficiente, quando o código já sabe que falta documento obrigatório, só
 *       criaria a chance de ele discordar;
 *   <li>nenhum trecho normativo suficientemente próximo da pergunta: sem contexto, o modelo não teria
 *       em que se apoiar, e a resposta honesta é declarar que a informação não está na base.
 * </ul>
 *
 * <p><strong>Barreiras antes de gravar.</strong> Além do schema, o caso de uso confere que a resposta
 * é da pergunta certa e que todo trecho citado estava entre os que foram fornecidos — um trecho
 * citado que ninguém mostrou ao modelo é alucinação com aparência de fundamento. Uma resposta
 * inválida ganha uma única nova tentativa, com o prompt reforçado; falhando de novo, nada é gravado
 * para aquela pergunta e a demanda recebe um alerta.
 *
 * <p><strong>Segunda checagem antes de entregar.</strong> Com todas as perguntas respondidas, as
 * respostas críticas passam pelo {@link AiAnalysisVerifier} (Prompt 14) e só então a demanda avança:
 * uma resposta reprovada precisa chegar ao revisor já sinalizada, e não sinalizada depois.
 *
 * <p><strong>Retomável.</strong> Perguntas já respondidas não voltam ao modelo (índice único em
 * {@code (legal_case_id, question_key)}). Com um alerta desta etapa em aberto, nenhuma chamada nova é
 * feita: o caso espera tratamento humano, e repetir custaria dinheiro sem mudar o desfecho.
 */
public class AnalyzeLegalCaseUseCase {

    static final String SYSTEM_SECTION = "SISTEMA";
    static final String USER_SECTION = "USUARIO";
    static final String REINFORCEMENT_SECTION = "REFORCO";

    /** Alertas desta etapa: qualquer um deles em aberto suspende as chamadas ao modelo. */
    private static final Set<LegalCaseAlertType> ANALYSIS_ALERTS =
            Set.of(LegalCaseAlertType.AI_ANALYSIS_INVALID_OUTPUT, LegalCaseAlertType.AI_ANALYSIS_REFUSED);

    /**
     * Tamanho máximo do texto de consulta enviado à recuperação normativa.
     *
     * <p>É curto de propósito. A consulta começa pela pergunta e completa com o contexto da demanda;
     * despejar todos os fatos diluiria a pergunta no meio de centenas de palavras de contrato, e a
     * busca por similaridade passaria a responder "que norma se parece com este contrato?" em vez de
     * "que norma trata desta pergunta?".
     */
    private static final int MAX_QUERY_CHARACTERS = 800;

    private final LegalCaseRepository legalCaseRepository;
    private final DocumentRepository documentRepository;
    private final AiExtractedFactRepository factRepository;
    private final DocumentChecklistService checklistService;
    private final KnowledgeBaseRetriever retriever;
    private final AiAnalysisResponseRepository responseRepository;
    private final LegalCaseAlertRepository alertRepository;
    private final PromptVersionRepository promptVersionRepository;
    private final LlmClientPort llmClient;
    private final StructuredOutputValidator validator;
    private final LegalAnalysisAnswerReader answerReader;
    private final AiAnalysisVerifier verifier;
    private final LegalCaseStatusTransitionService statusTransitionService;
    private final LegalCaseStatusHistoryRepository statusHistoryRepository;
    private final TransactionRunner transactionRunner;
    private final Clock clock;
    private final Supplier<UUID> idGenerator;

    public AnalyzeLegalCaseUseCase(
            LegalCaseRepository legalCaseRepository,
            DocumentRepository documentRepository,
            AiExtractedFactRepository factRepository,
            DocumentChecklistService checklistService,
            KnowledgeBaseRetriever retriever,
            AiAnalysisResponseRepository responseRepository,
            LegalCaseAlertRepository alertRepository,
            PromptVersionRepository promptVersionRepository,
            LlmClientPort llmClient,
            StructuredOutputValidator validator,
            LegalAnalysisAnswerReader answerReader,
            AiAnalysisVerifier verifier,
            LegalCaseStatusTransitionService statusTransitionService,
            LegalCaseStatusHistoryRepository statusHistoryRepository,
            TransactionRunner transactionRunner,
            Clock clock,
            Supplier<UUID> idGenerator) {
        this.legalCaseRepository = Objects.requireNonNull(legalCaseRepository, "legalCaseRepository não pode ser nulo");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository não pode ser nulo");
        this.factRepository = Objects.requireNonNull(factRepository, "factRepository não pode ser nulo");
        this.checklistService = Objects.requireNonNull(checklistService, "checklistService não pode ser nulo");
        this.retriever = Objects.requireNonNull(retriever, "retriever não pode ser nulo");
        this.responseRepository = Objects.requireNonNull(responseRepository, "responseRepository não pode ser nulo");
        this.alertRepository = Objects.requireNonNull(alertRepository, "alertRepository não pode ser nulo");
        this.promptVersionRepository =
                Objects.requireNonNull(promptVersionRepository, "promptVersionRepository não pode ser nulo");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient não pode ser nulo");
        this.validator = Objects.requireNonNull(validator, "validator não pode ser nulo");
        this.answerReader = Objects.requireNonNull(answerReader, "answerReader não pode ser nulo");
        this.verifier = Objects.requireNonNull(verifier, "verifier não pode ser nulo");
        this.statusTransitionService =
                Objects.requireNonNull(statusTransitionService, "statusTransitionService não pode ser nulo");
        this.statusHistoryRepository =
                Objects.requireNonNull(statusHistoryRepository, "statusHistoryRepository não pode ser nulo");
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner não pode ser nulo");
        this.clock = Objects.requireNonNull(clock, "clock não pode ser nulo");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator não pode ser nulo");
    }

    /**
     * Responde as perguntas ainda não respondidas e, se todas forem respondidas sem alerta, leva a
     * demanda para {@code PENDING_HUMAN_REVIEW}.
     *
     * @throws LegalCaseNotFoundException se a demanda não existir
     * @throws InvalidStatusTransitionException se a demanda não estiver em
     *     {@code AI_ANALYSIS_IN_PROGRESS} (nem já em {@code PENDING_HUMAN_REVIEW}, caso em que a
     *     etapa já foi concluída e nada é feito)
     * @throws PromptVersionNotFoundException se não houver versão ativa do prompt
     * @throws com.lexflow.application.llm.LlmUnavailableException se o provedor de LLM estiver
     *     indisponível
     * @throws com.lexflow.application.knowledge.EmbeddingUnavailableException se o provedor de
     *     embeddings estiver indisponível
     */
    public LegalCaseAnalysisResult analyze(UUID legalCaseId) {
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        LegalCase legalCase = legalCaseRepository
                .findById(legalCaseId)
                .orElseThrow(() -> new LegalCaseNotFoundException(legalCaseId));

        if (legalCase.status() == LegalCaseStatus.PENDING_HUMAN_REVIEW) {
            return new LegalCaseAnalysisResult(
                    responseRepository.findByLegalCaseId(legalCaseId),
                    openAlerts(legalCaseId),
                    0,
                    VerificationSummary.NONE,
                    false);
        }
        if (legalCase.status() != LegalCaseStatus.AI_ANALYSIS_IN_PROGRESS) {
            throw new InvalidStatusTransitionException(legalCase.status(), LegalCaseStatus.PENDING_HUMAN_REVIEW);
        }

        Set<QuestionKey> questions = QuestionKey.applicableTo(legalCase.caseType());
        Map<QuestionKey, AiAnalysisResponse> answered = new LinkedHashMap<>();
        responseRepository.findByLegalCaseId(legalCaseId).forEach(response -> answered.put(response.questionKey(), response));

        // Um alerta desta etapa em aberto significa que a demanda espera um humano: repetir as
        // chamadas agora custaria dinheiro sem mudar o desfecho.
        boolean blocked = openAlerts(legalCaseId).stream()
                .anyMatch(alert -> ANALYSIS_ALERTS.contains(alert.type()));

        int llmCalls = 0;
        if (!blocked) {
            PromptTemplate template = PromptTemplate.of(promptVersionRepository
                    .findActive(LegalAnalysisSchema.PROMPT_KEY)
                    .orElseThrow(() -> new PromptVersionNotFoundException(LegalAnalysisSchema.PROMPT_KEY)));
            LegalCaseChecklist checklist = checklistService.findByLegalCaseId(legalCaseId);
            String facts = renderFacts(legalCaseId);
            String checklistBlock = renderChecklist(checklist);

            for (QuestionKey question : questions) {
                if (answered.containsKey(question)) {
                    continue;
                }
                Answered result = answerQuestion(legalCase, question, template, facts, checklistBlock, checklist);
                llmCalls += result.llmCalls();
                if (result.response() != null) {
                    answered.put(question, result.response());
                } else {
                    // A pergunta gerou alerta: as demais não são tentadas nesta execução.
                    break;
                }
            }
        }

        List<LegalCaseAlert> openAlerts = openAlerts(legalCaseId);
        List<AiAnalysisResponse> responses = responseRepository.findByLegalCaseId(legalCaseId);
        boolean complete = responses.stream().map(AiAnalysisResponse::questionKey).collect(java.util.stream.Collectors.toSet())
                .containsAll(questions);
        boolean advanced = complete && openAlerts.stream().noneMatch(LegalCaseAlert::blocksPipeline);

        VerificationSummary verification = VerificationSummary.NONE;
        if (advanced) {
            // A segunda checagem roda antes da transição: uma resposta reprovada chega ao revisor já
            // sinalizada. Depois dela, as respostas são relidas para devolver o selo atualizado.
            verification = verifier.verify(legalCaseId);
            responses = responseRepository.findByLegalCaseId(legalCaseId);
            advance(legalCase, responses.size());
        }
        return new LegalCaseAnalysisResult(responses, openAlerts, llmCalls, verification, advanced);
    }

    /**
     * Responde uma pergunta: por regra de código, quando ela é determinística ou a base não tem nada
     * próximo; pelo modelo, no resto dos casos.
     */
    private Answered answerQuestion(
            LegalCase legalCase,
            QuestionKey question,
            PromptTemplate template,
            String facts,
            String checklistBlock,
            LegalCaseChecklist checklist) {

        if (question == QuestionKey.HAS_SUFFICIENT_DOCUMENTATION && !checklist.hasSufficientDocumentation()) {
            return new Answered(saveDeterministic(legalCase, question, incompleteDocumentationAnswer(checklist)), 0);
        }

        List<RetrievedChunk> chunks = retriever.retrieve(queryFor(legalCase, question, facts));
        if (chunks.isEmpty()) {
            return new Answered(
                    saveDeterministic(
                            legalCase,
                            question,
                            "%s: nenhum trecho normativo indexado trata de \"%s\" para uma demanda do tipo %s. "
                                            .formatted(
                                                    AiAnalysisResponse.NOT_FOUND_IN_KNOWLEDGE_BASE,
                                                    question.statement(),
                                                    legalCase.caseType().name())
                                    + "Indexe a norma ou a política aplicável e execute a análise novamente."),
                    0);
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put("CASE_TYPE", legalCase.caseType().name());
        values.put("QUESTION_KEY", question.name());
        values.put("QUESTION_TEXT", question.statement());
        values.put("FACTS", facts);
        values.put("CHECKLIST", checklistBlock);
        values.put("CHUNKS", renderChunks(chunks));
        values.put("CHUNK_IDS", chunks.stream().map(chunk -> chunk.chunkId().toString()).collect(java.util.stream.Collectors.joining(", ")));

        String system = template.render(SYSTEM_SECTION, values);
        String user = template.render(USER_SECTION, values);
        Set<UUID> allowedChunks = chunks.stream().map(RetrievedChunk::chunkId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        try {
            Attempt first = attempt(system, user, question, allowedChunks);
            if (first.isValid()) {
                return new Answered(saveFromLlm(legalCase, question, first, template), 1);
            }

            Map<String, String> reinforcementValues = new LinkedHashMap<>(values);
            reinforcementValues.put("VIOLATIONS", bulletList(first.violations()));
            String reinforcement = template.render(REINFORCEMENT_SECTION, reinforcementValues);
            Attempt second = attempt(system, user + "\n\n" + reinforcement, question, allowedChunks);
            if (second.isValid()) {
                return new Answered(saveFromLlm(legalCase, question, second, template), 2);
            }

            openAlert(
                    legalCase,
                    LegalCaseAlertType.AI_ANALYSIS_INVALID_OUTPUT,
                    "Resposta a %s fora do formato exigido após 2 tentativas (%s, modelo %s). Violações: %s"
                            .formatted(
                                    question.name(),
                                    template.version().label(),
                                    second.model(),
                                    String.join("; ", second.violations())));
            return new Answered(null, 2);
        } catch (LlmRefusalException e) {
            openAlert(
                    legalCase,
                    LegalCaseAlertType.AI_ANALYSIS_REFUSED,
                    "O modelo %s se recusou a responder %s (categoria: %s)"
                            .formatted(e.model(), question.name(), e.category()));
            return new Answered(null, 1);
        }
    }

    /** Uma chamada, com a validação do schema somada às regras desta etapa. */
    private Attempt attempt(String system, String user, QuestionKey question, Set<UUID> allowedChunks) {
        try {
            LlmResponse response = llmClient.complete(
                    new LlmRequest(system, user, LegalAnalysisSchema.schema(), null, null, null, null));
            if (response.structuredOutput() == null) {
                return Attempt.invalid(response.model(), List.of("resposta sem conteúdo estruturado"));
            }
            List<String> violations =
                    new ArrayList<>(validator.violations(LegalAnalysisSchema.schema(), response.structuredOutput()));
            if (!violations.isEmpty()) {
                return Attempt.invalid(response.model(), violations);
            }

            LegalAnalysisAnswer answer = answerReader.read(response.structuredOutput());
            List<UUID> cited = new ArrayList<>();
            if (!question.name().equals(answer.questionKey())) {
                violations.add("question_key respondido (%s) diferente do pedido (%s)"
                        .formatted(answer.questionKey(), question.name()));
            }
            for (String citedChunk : answer.citedChunks()) {
                UUID chunkId = parseUuid(citedChunk);
                if (chunkId == null || !allowedChunks.contains(chunkId)) {
                    // Citar um trecho que não foi fornecido é alucinação com aparência de fundamento.
                    violations.add("trecho citado fora da lista fornecida: " + citedChunk);
                } else if (!cited.contains(chunkId)) {
                    cited.add(chunkId);
                }
            }
            boolean declaresNotFound =
                    answer.answer().trim().toLowerCase(java.util.Locale.ROOT)
                            .startsWith(AiAnalysisResponse.NOT_FOUND_IN_KNOWLEDGE_BASE);
            if (cited.isEmpty() && !declaresNotFound) {
                violations.add("resposta sem trecho citado e sem declarar que a informação não foi encontrada");
            }
            return violations.isEmpty()
                    ? Attempt.valid(response.model(), answer, cited)
                    : Attempt.invalid(response.model(), violations);
        } catch (LlmResponseValidationException e) {
            List<String> violations = e.violations().isEmpty() ? List.of(e.getMessage()) : e.violations();
            return Attempt.invalid(e.model(), violations);
        }
    }

    private AiAnalysisResponse saveFromLlm(
            LegalCase legalCase, QuestionKey question, Attempt attempt, PromptTemplate template) {
        return responseRepository.save(AiAnalysisResponse.fromLlm(
                idGenerator.get(),
                legalCase.id(),
                question,
                withReviewerNotes(attempt.answer()),
                ConfidenceScore.of(attempt.answer().confidenceScore()),
                attempt.citedChunks(),
                attempt.model(),
                template.version().id(),
                clock.instant()));
    }

    private AiAnalysisResponse saveDeterministic(LegalCase legalCase, QuestionKey question, String answerText) {
        return responseRepository.save(AiAnalysisResponse.deterministic(
                idGenerator.get(), legalCase.id(), question, answerText, clock.instant()));
    }

    /**
     * Junta ao texto da resposta os pontos que o modelo pediu para o revisor verificar.
     *
     * <p>Eles vão no próprio texto, e não em uma tabela à parte, porque são parte do que a pessoa
     * precisa ler junto com a resposta — separá-los criaria uma leitura em que o alerta pode passar
     * despercebido.
     */
    private static String withReviewerNotes(LegalAnalysisAnswer answer) {
        if (answer.alerts().isEmpty()) {
            return answer.answer();
        }
        return answer.answer() + "\n\nPontos para verificação:\n" + bulletList(answer.alerts());
    }

    /** Resposta determinística para documentação incompleta (Prompt 13, item 3). */
    private static String incompleteDocumentationAnswer(LegalCaseChecklist checklist) {
        List<String> missing = checklist.checklist().missingMandatoryDocumentTypes();
        if (!checklist.evaluated()) {
            return "Documentação incompleta: o checklist desta demanda ainda não foi gerado, "
                    + "então não é possível afirmar que a documentação obrigatória está completa.";
        }
        return "Documentação incompleta: faltam os documentos obrigatórios %s. "
                        .formatted(String.join(", ", missing))
                + "Esta resposta vem da regra de checklist, não do modelo: enquanto faltar documento obrigatório, "
                + "a documentação não é suficiente.";
    }

    /** Texto de consulta da recuperação normativa: a pergunta somada ao contexto da demanda. */
    private static String queryFor(LegalCase legalCase, QuestionKey question, String facts) {
        String query = "%s Tipo de demanda: %s. %s"
                .formatted(question.statement(), legalCase.caseType().name(), facts);
        return query.length() <= MAX_QUERY_CHARACTERS ? query : query.substring(0, MAX_QUERY_CHARACTERS);
    }

    /** Fatos extraídos de cada documento, identificados pelo nome do arquivo. */
    private String renderFacts(UUID legalCaseId) {
        Map<UUID, String> fileNames = new LinkedHashMap<>();
        for (Document document : documentRepository.findByLegalCaseId(legalCaseId)) {
            fileNames.put(document.id(), document.fileName());
        }
        List<AiExtractedFact> facts = factRepository.findByLegalCaseId(legalCaseId);
        if (facts.isEmpty()) {
            return "Nenhum fato foi extraído dos documentos desta demanda.";
        }
        StringBuilder rendered = new StringBuilder();
        for (AiExtractedFact fact : facts) {
            rendered.append("Documento: ")
                    .append(fileNames.getOrDefault(fact.documentId(), fact.documentId().toString()))
                    .append('\n')
                    .append(fact.extractedJson())
                    .append("\n\n");
        }
        return rendered.toString().strip();
    }

    /** Situação do checklist documental, para o modelo saber o que a demanda apresentou. */
    private static String renderChecklist(LegalCaseChecklist checklist) {
        if (!checklist.evaluated()) {
            return "Checklist ainda não gerado.";
        }
        List<String> missing = checklist.checklist().missingMandatoryDocumentTypes();
        return "Documentação obrigatória completa: %s.%s"
                .formatted(
                        checklist.hasSufficientDocumentation() ? "sim" : "não",
                        missing.isEmpty() ? "" : " Documentos obrigatórios faltantes: " + String.join(", ", missing) + ".");
    }

    /** Trechos normativos, cada um precedido do identificador que o modelo deve citar. */
    private static String renderChunks(List<RetrievedChunk> chunks) {
        StringBuilder rendered = new StringBuilder();
        for (RetrievedChunk chunk : chunks) {
            rendered.append('[')
                    .append(chunk.chunkId())
                    .append("] ")
                    .append(chunk.citation())
                    .append(" (")
                    .append(chunk.sourceType().name())
                    .append(")\n")
                    .append(chunk.content())
                    .append("\n\n");
        }
        return rendered.toString().strip();
    }

    private void openAlert(LegalCase legalCase, LegalCaseAlertType type, String message) {
        alertRepository.save(
                LegalCaseAlert.open(idGenerator.get(), legalCase.id(), null, type, message, clock.instant()));
    }

    private void advance(LegalCase legalCase, int answerCount) {
        transactionRunner.runInTransaction(() -> {
            LegalCaseStatusTransitionResult transition = statusTransitionService.transition(
                    legalCase,
                    LegalCaseStatus.PENDING_HUMAN_REVIEW,
                    LegalCaseStatusHistoryEntry.SYSTEM_ACTOR,
                    "Análise da IA concluída: %d pergunta(s) respondida(s)".formatted(answerCount));
            legalCaseRepository.save(transition.legalCase());
            statusHistoryRepository.save(transition.historyEntry());
        });
    }

    private List<LegalCaseAlert> openAlerts(UUID legalCaseId) {
        return alertRepository.findByLegalCaseId(legalCaseId).stream()
                .filter(LegalCaseAlert::isOpen)
                .toList();
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String bulletList(List<String> items) {
        List<String> lines = new ArrayList<>(items.size());
        items.forEach(item -> lines.add("- " + item));
        return String.join("\n", lines);
    }

    /** Resultado de uma chamada: a resposta lida e os trechos citados, ou a lista de violações. */
    private record Attempt(String model, LegalAnalysisAnswer answer, List<UUID> citedChunks, List<String> violations) {

        static Attempt valid(String model, LegalAnalysisAnswer answer, List<UUID> citedChunks) {
            return new Attempt(model, answer, List.copyOf(citedChunks), List.of());
        }

        static Attempt invalid(String model, List<String> violations) {
            return new Attempt(model, null, List.of(), List.copyOf(violations));
        }

        boolean isValid() {
            return answer != null;
        }
    }

    /** Resposta gravada para uma pergunta, ou nula quando a pergunta gerou alerta. */
    private record Answered(AiAnalysisResponse response, int llmCalls) {}
}
