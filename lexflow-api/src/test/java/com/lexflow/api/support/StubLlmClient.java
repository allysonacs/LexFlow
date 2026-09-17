package com.lexflow.api.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lexflow.application.llm.LlmClientPort;
import com.lexflow.application.llm.LlmRequest;
import com.lexflow.application.llm.LlmResponse;
import com.lexflow.application.llm.LlmStopReason;
import com.lexflow.application.llm.LlmUsage;
import com.lexflow.infrastructure.testsupport.DocumentFixtures;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM simulado dos testes da API: nenhum teste automatizado chama o provedor real (Prompt 11).
 *
 * <p>Sem roteiro, responde como um modelo bem-comportado, reconhecendo a etapa pelo schema recebido:
 *
 * <ul>
 *   <li><strong>extração de fatos</strong> (Prompt 11): para o contrato de teste
 *       ({@code contrato-texto-nativo.pdf}) devolve o gabarito; para qualquer outro documento, uma
 *       extração vazia no formato do schema;
 *   <li><strong>análise jurídica</strong> (Prompt 13): responde a pergunta do prompt citando o
 *       primeiro trecho normativo que lhe foi oferecido — um modelo bem-comportado nunca cita um
 *       identificador que não recebeu.
 * </ul>
 *
 * <p>Um teste pode enfileirar respostas próprias — que valem para as próximas chamadas, na ordem — e
 * deve chamar {@link #reset()} ao terminar.
 *
 * <p>É seguro entre threads: quem chama é o consumidor da fila.
 */
public class StubLlmClient implements LlmClientPort {

    /** Modelo que o dublê diz ter usado. */
    public static final String MODEL = "claude-opus-5";

    /** Trecho do contrato de teste que dispara o gabarito. */
    private static final String CONTRACT_MARKER = "CONTRATO DE PRESTAÇÃO DE SERVIÇOS";

    /** Marca do schema da análise jurídica (Prompt 13). */
    private static final String ANALYSIS_SCHEMA_MARKER = "cited_chunks";

    private static final Pattern QUESTION_KEY =
            Pattern.compile("\\(\"question_key\"\\):\\s*([A-Z_]+)");

    private static final Pattern CHUNK_IDS =
            Pattern.compile("Identificadores de trecho que você pode citar:\\s*(.+)");

    private final ObjectMapper objectMapper;
    private final Queue<Function<LlmRequest, LlmResponse>> script = new ConcurrentLinkedQueue<>();
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();

    public StubLlmClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        requests.add(request);
        Function<LlmRequest, LlmResponse> scripted = script.poll();
        return scripted != null ? scripted.apply(request) : wellBehaved(request);
    }

    /** Enfileira respostas para as próximas chamadas. */
    @SafeVarargs
    public final void enqueue(Function<LlmRequest, LlmResponse>... answers) {
        script.addAll(List.of(answers));
    }

    public List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    public void reset() {
        script.clear();
        requests.clear();
    }

    /** Resposta com o JSON informado, como o cliente real devolveria. */
    public static LlmResponse response(String json) {
        return new LlmResponse(
                "msg_stub", MODEL, MODEL, json, json, LlmStopReason.END_TURN,
                new LlmUsage(1_000, 200, 0, 0), "req_stub", Duration.ofMillis(1));
    }

    private LlmResponse wellBehaved(LlmRequest request) {
        if (request.outputSchema().contains(ANALYSIS_SCHEMA_MARKER)) {
            return response(legalAnswer(request));
        }
        if (request.prompt().contains(CONTRACT_MARKER) && request.outputSchema().contains("penaltyClause")) {
            return response(DocumentFixtures.readFactsAnswerKey(DocumentFixtures.CONTRACT_FACTS_ANSWER_KEY));
        }
        return response(emptyFacts(request.outputSchema()));
    }

    /** Resposta jurídica citando o primeiro trecho oferecido no prompt. */
    private String legalAnswer(LlmRequest request) {
        Matcher question = QUESTION_KEY.matcher(request.systemPrompt());
        String questionKey = question.find() ? question.group(1) : "UNKNOWN";
        List<String> chunkIds = chunkIdsOf(request.prompt());
        try {
            ObjectNode answer = objectMapper.createObjectNode();
            answer.put("question_key", questionKey);
            answer.put(
                    "answer",
                    chunkIds.isEmpty()
                            ? "informação não encontrada na base normativa para esta pergunta."
                            : "Resposta fundamentada nos trechos normativos citados.");
            answer.put("confidence_score", chunkIds.isEmpty() ? 0.1 : 0.8);
            ArrayNode cited = answer.putArray("cited_chunks");
            chunkIds.stream().findFirst().ifPresent(cited::add);
            answer.putArray("alerts");
            return objectMapper.writeValueAsString(answer);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> chunkIdsOf(String prompt) {
        Matcher matcher = CHUNK_IDS.matcher(prompt);
        if (!matcher.find()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String id : matcher.group(1).split(",")) {
            if (!id.isBlank()) {
                ids.add(id.strip());
            }
        }
        return List.copyOf(ids);
    }

    /** Extração sem fatos, com cada campo específico do schema em {@code null}. */
    private String emptyFacts(String schema) {
        try {
            JsonNode specificFields = objectMapper.readTree(schema).path("properties").path("specificFacts").path("properties");
            ObjectNode specific = objectMapper.createObjectNode();
            specificFields.fieldNames().forEachRemaining(specific::putNull);
            ObjectNode facts = objectMapper.createObjectNode();
            facts.putNull("documentKind");
            facts.putArray("parties");
            facts.putArray("monetaryValues");
            facts.putArray("relevantDates");
            facts.putArray("keyClauses");
            facts.set("specificFacts", specific);
            return objectMapper.writeValueAsString(facts);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
