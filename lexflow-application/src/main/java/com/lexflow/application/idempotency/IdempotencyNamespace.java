package com.lexflow.application.idempotency;

/**
 * Escopo de uma chave de idempotência.
 *
 * <p>As chaves de todos os endpoints convivem em um índice único só, e quem as escolhe é o cliente.
 * O prefixo é o que impede que a mesma chave, usada em duas operações diferentes — ou por uma
 * mensagem de fila —, seja confundida com uma repetição.
 *
 * @param prefix prefixo gravado junto da chave do cliente
 * @param eventType valor gravado em {@code processing_events.event_type}, que diz de onde veio o registro
 */
public enum IdempotencyNamespace {

    /** Ingestão de uma nova demanda (Prompt 05). */
    LEGAL_CASE_INGESTION("legal-case-ingestion:", "LEGAL_CASE_INGESTION_REQUEST"),

    /** Registro da decisão humana (Prompt 15). */
    LEGAL_CASE_DECISION("legal-case-decision:", "LEGAL_CASE_DECISION_REQUEST"),

    /** Reenvio de documentação de uma demanda devolvida (Prompt 15). */
    LEGAL_CASE_RESUBMISSION("legal-case-resubmission:", "LEGAL_CASE_RESUBMISSION_REQUEST"),

    /** Indexação de uma fonte normativa (Prompt 12). */
    KNOWLEDGE_BASE_SOURCE("knowledge-base-source:", "KNOWLEDGE_BASE_SOURCE_REQUEST");

    private final String prefix;
    private final String eventType;

    IdempotencyNamespace(String prefix, String eventType) {
        this.prefix = prefix;
        this.eventType = eventType;
    }

    public String eventType() {
        return eventType;
    }

    /** Chave como ela é gravada no banco. */
    public String storedKey(String idempotencyKey) {
        return prefix + idempotencyKey;
    }
}
