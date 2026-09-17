package com.lexflow.domain.audit;

/**
 * Ação registrada na trilha de auditoria.
 *
 * <p>É um enum, e não texto livre, porque a trilha só serve se for consultável: uma ação escrita de
 * três formas diferentes torna impossível responder "quantas demandas foram devolvidas no mês".
 */
public enum AuditAction {

    /** Demanda criada pela ingestão. */
    LEGAL_CASE_RECEIVED,

    /** Demanda mudou de status. */
    STATUS_CHANGED,

    /** Resposta da IA a uma pergunta jurídica foi gravada. */
    AI_ANSWER_RECORDED,

    /** Resposta da IA foi submetida à segunda checagem (Prompt 14). */
    AI_ANSWER_VERIFIED,

    /** Decisão de um responsável humano foi registrada. */
    DECISION_REGISTERED
}
