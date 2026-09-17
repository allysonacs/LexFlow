package com.lexflow.application.verification;

/**
 * JSON Schema fixo da segunda checagem (Prompt 14, item 2).
 *
 * <p>São dois campos e nada mais: o veredito e a sua justificativa. O formato estreito é proposital —
 * ele impede que a verificação se transforme, na prática, em uma segunda resposta jurídica.
 */
public final class AnswerVerificationSchema {

    /** Chave do prompt em {@code prompt_versions}. */
    public static final String PROMPT_KEY = "ANSWER_VERIFICATION";

    private static final String SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "supported": {
                  "type": "boolean",
                  "description": "true somente se tudo o que a resposta afirma puder ser lido nos trechos citados"
                },
                "justification": {
                  "type": "string",
                  "minLength": 1,
                  "description": "Em uma ou duas frases, o que sustentou ou o que faltou"
                }
              },
              "required": ["supported", "justification"],
              "additionalProperties": false
            }
            """;

    private AnswerVerificationSchema() {
        // classe de constantes
    }

    public static String schema() {
        return SCHEMA;
    }
}
