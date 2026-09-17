package com.lexflow.application.audit;

import com.lexflow.domain.audit.AuditLog;
import java.util.List;
import java.util.UUID;

/**
 * Porta de leitura da trilha, usada pelo {@code GET /api/v1/legal-cases/{id}/audit-log}.
 *
 * <p>Separada da escrita de propósito: quem grava não lê, e quem lê não grava. Não existe, em lugar
 * nenhum do sistema, uma operação de alteração ou remoção.
 */
public interface AuditLogReader {

    /** Linha do tempo completa de uma demanda, da ação mais antiga para a mais recente. */
    List<AuditLog> findByLegalCaseId(UUID legalCaseId);
}
