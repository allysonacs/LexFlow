package com.lexflow.application.analysis.support;

import com.lexflow.application.analysis.AiAnalysisResponseRepository;
import com.lexflow.application.analysis.LegalAnalysisAnswer;
import com.lexflow.application.analysis.LegalAnalysisAnswerReader;
import com.lexflow.domain.ai.AiAnalysisResponse;
import com.lexflow.domain.ai.PromptVersion;
import com.lexflow.domain.ai.QuestionKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dublês da cadeia de prompts: respostas em memória, leitor de JSON e o prompt da migration V7. */
public final class AnalysisTestDoubles {

    /** Cópia reduzida do template da migration {@code V7__legal_analysis.sql}. */
    public static final String TEMPLATE_V1 =
            """
### SISTEMA ###
Você apoia a área jurídica respondendo a uma pergunta específica sobre uma demanda. Você não decide nada.
Responda exclusivamente com base nos TRECHOS NORMATIVOS e nos FATOS fornecidos, citando os identificadores usados.
Se os trechos não permitirem responder, comece com "informação não encontrada na base normativa" e deixe "cited_chunks" vazio.

Tipo de demanda: {{CASE_TYPE}}
Pergunta a responder ("question_key"): {{QUESTION_KEY}}
Enunciado da pergunta: {{QUESTION_TEXT}}
### USUARIO ###
<fatos>
{{FACTS}}
</fatos>

<checklist>
{{CHECKLIST}}
</checklist>

<trechos>
{{CHUNKS}}
</trechos>

Identificadores de trecho que você pode citar: {{CHUNK_IDS}}

Responda à pergunta {{QUESTION_KEY}} no formato definido.
### REFORCO ###
A resposta anterior não foi aceita. Problemas encontrados:
{{VIOLATIONS}}

Responda somente com um objeto JSON no formato definido, com "question_key" igual a {{QUESTION_KEY}}.""";

    public static final PromptVersion PROMPT_V1 = new PromptVersion(
            UUID.fromString("7c1d0e5a-0011-4f00-8000-000000000002"),
            "LEGAL_ANALYSIS",
            1,
            TEMPLATE_V1,
            true,
            Instant.parse("2026-09-17T00:00:00Z"));

    private AnalysisTestDoubles() {
        // classe utilitária
    }

    /** Respostas em memória, com a mesma unicidade do banco: uma por pergunta em cada demanda. */
    public static final class InMemoryAiAnalysisResponseRepository implements AiAnalysisResponseRepository {

        private final Map<String, AiAnalysisResponse> responses = new LinkedHashMap<>();

        @Override
        public AiAnalysisResponse save(AiAnalysisResponse response) {
            String key = response.legalCaseId() + "|" + response.questionKey();
            if (responses.containsKey(key)) {
                throw new IllegalStateException("pergunta já respondida: " + key);
            }
            responses.put(key, response);
            return response;
        }

        @Override
        public List<AiAnalysisResponse> findByLegalCaseId(UUID legalCaseId) {
            return responses.values().stream()
                    .filter(response -> response.legalCaseId().equals(legalCaseId))
                    .toList();
        }

        @Override
        public Optional<AiAnalysisResponse> findByLegalCaseIdAndQuestionKey(UUID legalCaseId, QuestionKey questionKey) {
            return Optional.ofNullable(responses.get(legalCaseId + "|" + questionKey));
        }
    }

    /**
     * Leitor do JSON da resposta jurídica, suficiente para o formato plano do schema.
     *
     * <p>Em produção quem faz isso é o Jackson, na infraestrutura; aqui basta ler os cinco campos,
     * e assim a camada de aplicação continua se testando sem nenhuma dependência.
     */
    public static final class SimpleLegalAnalysisAnswerReader implements LegalAnalysisAnswerReader {

        private static final Pattern STRING_FIELD = Pattern.compile("\"%s\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        private static final Pattern NUMBER_FIELD = Pattern.compile("\"%s\"\\s*:\\s*([0-9.eE+-]+)");
        private static final Pattern ARRAY_FIELD = Pattern.compile("\"%s\"\\s*:\\s*\\[([^\\]]*)]");
        private static final Pattern QUOTED = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

        @Override
        public LegalAnalysisAnswer read(String json) {
            if (json == null || !json.trim().startsWith("{")) {
                throw new IllegalArgumentException("resposta jurídica não é um objeto JSON");
            }
            return new LegalAnalysisAnswer(
                    string(json, "question_key"),
                    string(json, "answer"),
                    number(json, "confidence_score"),
                    array(json, "cited_chunks"),
                    array(json, "alerts"));
        }

        private static String string(String json, String field) {
            Matcher matcher = Pattern.compile(STRING_FIELD.pattern().formatted(field)).matcher(json);
            return matcher.find() ? unescape(matcher.group(1)) : "";
        }

        private static double number(String json, String field) {
            Matcher matcher = Pattern.compile(NUMBER_FIELD.pattern().formatted(field)).matcher(json);
            return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0.0;
        }

        private static List<String> array(String json, String field) {
            Matcher matcher = Pattern.compile(ARRAY_FIELD.pattern().formatted(field)).matcher(json);
            if (!matcher.find()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            Matcher items = QUOTED.matcher(matcher.group(1));
            while (items.find()) {
                values.add(unescape(items.group(1)));
            }
            return List.copyOf(values);
        }

        private static String unescape(String value) {
            return value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
        }
    }

    /** Monta o JSON de uma resposta jurídica, no formato do schema. */
    public static String answerJson(
            QuestionKey questionKey, String answer, double confidence, List<UUID> citedChunks, List<String> alerts) {
        return """
                {"question_key": "%s", "answer": "%s", "confidence_score": %s, "cited_chunks": [%s], "alerts": [%s]}
                """
                .formatted(
                        questionKey.name(),
                        escape(answer),
                        confidence,
                        citedChunks.stream().map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(", ")),
                        alerts.stream().map(alert -> "\"" + escape(alert) + "\"").collect(java.util.stream.Collectors.joining(", ")));
    }

    /** Resposta bem-comportada, citando os trechos informados. */
    public static String answerJson(QuestionKey questionKey, String answer, List<UUID> citedChunks) {
        return answerJson(questionKey, answer, 0.8, citedChunks, List.of());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
