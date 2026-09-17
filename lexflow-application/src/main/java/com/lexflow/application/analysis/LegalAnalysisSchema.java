package com.lexflow.application.analysis;

/**
 * JSON Schema fixo das respostas às perguntas jurídicas (seção 10, item 3).
 *
 * <p>É fixo de propósito: ao contrário do schema de extração de fatos, que varia por tipo de demanda,
 * o formato de uma resposta é sempre o mesmo — pergunta, resposta, confiança, trechos citados e
 * alertas. Toda resposta é validada contra ele antes de ser persistida.
 *
 * <p>{@code additionalProperties: false} e os limites de cada campo não são adorno: eles transformam
 * "o modelo deveria responder assim" em algo que o código consegue recusar.
 */
public final class LegalAnalysisSchema {

    /** Chave do prompt em {@code prompt_versions}. */
    public static final String PROMPT_KEY = "LEGAL_ANALYSIS";

    private static final String SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "question_key": {
                  "type": "string",
                  "description": "Identificador da pergunta respondida, exatamente como informado no prompt"
                },
                "answer": {
                  "type": "string",
                  "minLength": 1,
                  "description": "Resposta em português, começando pela conclusão"
                },
                "confidence_score": {
                  "type": "number",
                  "minimum": 0.0,
                  "maximum": 1.0,
                  "description": "Quanto os trechos fornecidos sustentam a resposta"
                },
                "cited_chunks": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Identificadores dos trechos normativos que sustentam a resposta"
                },
                "alerts": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "O que o revisor humano precisa verificar por conta própria"
                }
              },
              "required": ["question_key", "answer", "confidence_score", "cited_chunks", "alerts"],
              "additionalProperties": false
            }
            """;

    private LegalAnalysisSchema() {
        // classe de constantes
    }

    /** O schema, em texto, como o cliente LLM e o validador o esperam. */
    public static String schema() {
        return SCHEMA;
    }
}
