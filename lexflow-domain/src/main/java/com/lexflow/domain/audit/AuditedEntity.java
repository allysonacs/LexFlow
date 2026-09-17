package com.lexflow.domain.audit;

/** Tipo de entidade a que uma linha de auditoria se refere. */
public enum AuditedEntity {

    /** A própria demanda jurídica: criação e transições de status. */
    LEGAL_CASE,

    /** Uma resposta da IA a uma pergunta jurídica. */
    AI_ANALYSIS_RESPONSE,

    /** A decisão de um responsável humano. */
    DECISION
}
