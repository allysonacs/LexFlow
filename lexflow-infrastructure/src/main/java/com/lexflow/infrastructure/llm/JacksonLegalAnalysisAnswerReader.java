package com.lexflow.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.application.analysis.LegalAnalysisAnswer;
import com.lexflow.application.analysis.LegalAnalysisAnswerReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Lê o JSON de uma resposta jurídica com Jackson (Prompt 13).
 *
 * <p>Existe para que {@code lexflow-application} continue sem conhecer biblioteca de serialização
 * (seção 7). A leitura é tolerante apenas no que o schema já garante: campos ausentes viram valores
 * vazios, porque o JSON chega aqui depois de validado.
 */
public class JacksonLegalAnalysisAnswerReader implements LegalAnalysisAnswerReader {

    private final ObjectMapper objectMapper;

    public JacksonLegalAnalysisAnswerReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public LegalAnalysisAnswer read(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("resposta jurídica não é JSON válido", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("resposta jurídica não é um objeto JSON");
        }
        return new LegalAnalysisAnswer(
                root.path("question_key").asText(""),
                root.path("answer").asText(""),
                root.path("confidence_score").asDouble(0.0),
                textArray(root.path("cited_chunks")),
                textArray(root.path("alerts")));
    }

    private static List<String> textArray(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(node.size());
        node.forEach(item -> values.add(item.asText("")));
        return List.copyOf(values);
    }
}
