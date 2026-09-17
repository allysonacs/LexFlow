package com.lexflow.application.llm;

import java.util.List;

/**
 * Porta que confere um JSON contra um JSON Schema.
 *
 * <p>Existe para que o caso de uso valide o que vai gravar sem depender só da validação feita pelo
 * cliente LLM: a regra "nada fora do schema é persistido" é do pipeline, e não do adapter.
 */
public interface StructuredOutputValidator {

    /**
     * Valida o JSON.
     *
     * @return as violações encontradas, descritas sem os valores do JSON; vazia quando é válido
     */
    List<String> violations(String jsonSchema, String json);
}
