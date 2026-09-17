package com.lexflow.infrastructure.audit;

import com.lexflow.application.audit.AuditLogWriter;
import com.lexflow.application.legalcase.LegalCaseStatusHistoryEntry;
import com.lexflow.application.legalcase.LegalCaseStatusHistoryRepository;
import com.lexflow.domain.audit.AuditAction;
import com.lexflow.domain.audit.AuditLog;
import com.lexflow.domain.audit.AuditedEntity;
import com.lexflow.infrastructure.persistence.adapter.LegalCaseStatusHistoryRepositoryAdapter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Audita a criação da demanda e cada transição de status (Prompt 16, item 1).
 *
 * <p>A auditoria fica aqui, e não dentro dos casos de uso, por uma razão prática: a seção 4 exige que
 * <em>toda</em> transição gere uma linha de histórico. Auditar a gravação do histórico cobre, por
 * construção, todas as transições — inclusive as que forem acrescentadas depois — sem que nenhum caso
 * de uso precise lembrar de chamar a trilha.
 *
 * <p>O registro inicial, aquele sem status anterior, é a criação da demanda.
 *
 * <p>Este decorador não é o {@code @Primary}: quem responde pela porta é o
 * {@link com.lexflow.infrastructure.observability.MeteredLegalCaseStatusHistoryRepository}, que
 * envolve este. A cadeia é {@code Metered → Auditing → JPA}, e as duas pontas observam sem participar.
 */
@Component
public class AuditingLegalCaseStatusHistoryRepository implements LegalCaseStatusHistoryRepository {

    private final LegalCaseStatusHistoryRepositoryAdapter delegate;
    private final AuditLogWriter auditLogWriter;
    private final Supplier<UUID> idGenerator;

    public AuditingLegalCaseStatusHistoryRepository(
            LegalCaseStatusHistoryRepositoryAdapter delegate, AuditLogWriter auditLogWriter) {
        this.delegate = delegate;
        this.auditLogWriter = auditLogWriter;
        this.idGenerator = UUID::randomUUID;
    }

    @Override
    public void save(LegalCaseStatusHistoryEntry entry) {
        delegate.save(entry);
        auditLogWriter.record(new AuditLog(
                idGenerator.get(),
                AuditedEntity.LEGAL_CASE,
                entry.legalCaseId(),
                entry.legalCaseId(),
                entry.isInitial() ? AuditAction.LEGAL_CASE_RECEIVED : AuditAction.STATUS_CHANGED,
                entry.changedBy(),
                payloadOf(entry),
                entry.changedAt()));
    }

    @Override
    public List<LegalCaseStatusHistoryEntry> findByLegalCaseId(UUID legalCaseId) {
        return delegate.findByLegalCaseId(legalCaseId);
    }

    /** Só metadados: status, motivo e autor. Nada de conteúdo de documento (seção 12). */
    private static Map<String, String> payloadOf(LegalCaseStatusHistoryEntry entry) {
        Map<String, String> payload = new LinkedHashMap<>();
        if (entry.previousStatus() != null) {
            payload.put("previousStatus", entry.previousStatus().name());
        }
        payload.put("newStatus", entry.newStatus().name());
        if (entry.reason() != null && !entry.reason().isBlank()) {
            payload.put("reason", entry.reason());
        }
        return payload;
    }
}
