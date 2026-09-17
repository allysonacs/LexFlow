package com.lexflow.application.audit;

import com.lexflow.domain.audit.AuditLog;

/**
 * Porta de saída da trilha de auditoria.
 *
 * <p><strong>A implementação não lança.</strong> É o que sustenta a restrição do Prompt 16: a trilha
 * observa o fluxo, não participa dele. Se gravar a linha falhar, a operação observada segue, e a
 * falha aparece no log da aplicação — o contrário faria uma indisponibilidade da auditoria derrubar
 * a ingestão de demandas.
 */
public interface AuditLogWriter {

    /** Grava uma linha da trilha. */
    void record(AuditLog auditLog);
}
