package com.lexflow.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.application.verification.AnswerVerification;
import com.lexflow.application.verification.AnswerVerificationReader;

/**
 * Lê o JSON da segunda checagem com Jackson (Prompt 14).
 *
 * <p>Existe pelo mesmo motivo do leitor das respostas jurídicas: manter {@code lexflow-application}
 * sem dependência de biblioteca de serialização (seção 7).
 */
public class JacksonAnswerVerificationReader implements AnswerVerificationReader {

    private final ObjectMapper objectMapper;

    public JacksonAnswerVerificationReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public AnswerVerification read(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("veredito da verificação não é JSON válido", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("veredito da verificação não é um objeto JSON");
        }
        return new AnswerVerification(
                root.path("supported").asBoolean(false), root.path("justification").asText(""));
    }
}
