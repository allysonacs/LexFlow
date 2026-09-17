package com.lexflow.infrastructure.audit;

import com.lexflow.application.audit.AuditLogWriter;
import com.lexflow.application.review.DecisionRepository;
import com.lexflow.domain.audit.AuditAction;
import com.lexflow.domain.audit.AuditLog;
import com.lexflow.domain.audit.AuditedEntity;
import com.lexflow.domain.decision.Decision;
import com.lexflow.infrastructure.persistence.adapter.DecisionRepositoryAdapter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Audita cada decisão humana registrada (Prompt 16, item 1).
 *
 * <p>O comentário da decisão não entra no payload: ele pode conter informação sensível do caso, e a
 * trilha registra o que aconteceu, não o que foi escrito (seção 12). Quem precisa do texto o lê na
 * própria decisão.
 */
@Component
@Primary
public class AuditingDecisionRepository implements DecisionRepository {

    private final DecisionRepositoryAdapter delegate;
    private final AuditLogWriter auditLogWriter;
    private final Supplier<UUID> idGenerator;

    public AuditingDecisionRepository(DecisionRepositoryAdapter delegate, AuditLogWriter auditLogWriter) {
        this.delegate = delegate;
        this.auditLogWriter = auditLogWriter;
        this.idGenerator = UUID::randomUUID;
    }

    @Override
    public Decision save(Decision decision) {
        Decision saved = delegate.save(decision);
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("decisionType", saved.decisionType().name());
        payload.put("resultingStatus", saved.decisionType().resultingStatus().name());
        payload.put("hasComments", Boolean.toString(saved.comments() != null && !saved.comments().isBlank()));
        auditLogWriter.record(new AuditLog(
                idGenerator.get(),
                AuditedEntity.DECISION,
                saved.id(),
                saved.legalCaseId(),
                AuditAction.DECISION_REGISTERED,
                saved.decidedBy(),
                payload,
                saved.decidedAt()));
        return saved;
    }

    @Override
    public Optional<Decision> findById(UUID id) {
        return delegate.findById(id);
    }

    @Override
    public List<Decision> findByLegalCaseId(UUID legalCaseId) {
        return delegate.findByLegalCaseId(legalCaseId);
    }
}
