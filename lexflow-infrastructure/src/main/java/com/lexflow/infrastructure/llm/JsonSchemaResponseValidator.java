package com.lexflow.infrastructure.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.application.llm.LlmResponseValidationException;
import com.lexflow.application.llm.StructuredOutputValidator;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Confere uma resposta do LLM contra o JSON Schema pedido.
 *
 * <p>O provedor já restringe a saída ao schema, mas a garantia do pipeline não pode depender só dele:
 * uma resposta truncada, uma recusa ou uma restrição que o provedor não aplica (como tamanho mínimo
 * de texto) passariam adiante. Esta validação é a última barreira antes de um dado inválido entrar no
 * sistema.
 *
 * <p>Os schemas compilados ficam em cache: são poucos e se repetem a cada chamada.
 *
 * <p>Também atende a porta {@link StructuredOutputValidator}, usada pelos casos de uso para validar o
 * que vão gravar sem depender só do cliente LLM.
 */
public class JsonSchemaResponseValidator implements StructuredOutputValidator {

    /** Limite de violações relatadas: o suficiente para diagnóstico, sem inflar a exceção. */
    private static final int MAX_REPORTED_VIOLATIONS = 10;

    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final Map<String, JsonSchema> compiledSchemas = new ConcurrentHashMap<>();

    public JsonSchemaResponseValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Converte o texto do schema em árvore JSON, para ser enviado ao provedor.
     *
     * @throws IllegalArgumentException se o schema não for JSON válido — é defeito de quem montou o
     *     pedido, e a chamada nem é feita
     */
    public JsonNode parseSchema(String schema) {
        try {
            JsonNode node = objectMapper.readTree(schema);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("outputSchema deve ser um objeto JSON");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("outputSchema não é JSON válido: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Valida o texto da resposta e devolve o JSON normalizado.
     *
     * @throws LlmResponseValidationException se o texto não for JSON ou violar o schema
     */
    public String requireValid(String schema, String responseText, String model) {
        JsonNode response = parse(responseText, model);
        List<String> violations = violationsOf(schema, response);
        if (!violations.isEmpty()) {
            throw new LlmResponseValidationException(
                    "A resposta do LLM não segue o schema esperado (%d violação(ões))".formatted(violations.size()),
                    model,
                    violations);
        }
        return response.toString();
    }

    @Override
    public List<String> violations(String jsonSchema, String json) {
        try {
            return violationsOf(jsonSchema, parse(json, null));
        } catch (LlmResponseValidationException e) {
            return e.violations();
        }
    }

    private JsonNode parse(String text, String model) {
        JsonNode node;
        try {
            node = objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new LlmResponseValidationException(
                    "A resposta do LLM não é JSON válido", model, List.of("JSON inválido: " + e.getOriginalMessage()));
        }
        if (node == null || node.isMissingNode()) {
            throw new LlmResponseValidationException("A resposta do LLM veio vazia", model, List.of("resposta vazia"));
        }
        return node;
    }

    private List<String> violationsOf(String schema, JsonNode json) {
        Set<ValidationMessage> messages = compiledSchemas
                .computeIfAbsent(schema, key -> schemaFactory.getSchema(parseSchema(key)))
                .validate(json);
        // Só a mensagem de cada violação (caminho e regra), nunca o valor recebido.
        return messages.stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .limit(MAX_REPORTED_VIOLATIONS)
                .toList();
    }
}
