package com.lexflow.infrastructure.audit;

import com.lexflow.application.analysis.AiAnalysisResponseRepository;
import com.lexflow.application.audit.AuditLogWriter;
import com.lexflow.domain.ai.AiAnalysisResponse;
import com.lexflow.domain.ai.QuestionKey;
import com.lexflow.domain.ai.VerificationStatus;
import com.lexflow.domain.audit.AuditAction;
import com.lexflow.domain.audit.AuditLog;
import com.lexflow.domain.audit.AuditedEntity;
import com.lexflow.infrastructure.persistence.adapter.AiAnalysisResponseRepositoryAdapter;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Audita cada resposta da IA gravada (Prompt 16, item 1) e cada selo da segunda checagem.
 *
 * <p>O ator é {@code AI} quando a resposta veio do modelo e {@code SYSTEM} quando ela veio de uma
 * regra de código: a trilha precisa deixar claro quem produziu cada afirmação.
 *
 * <p>O texto da resposta não vai para o payload — ele está na própria tabela, e a trilha registra o
 * que mudou, não o conteúdo.
 */
@Component
@Primary
public class AuditingAiAnalysisResponseRepository implements AiAnalysisResponseRepository {

    private final AiAnalysisResponseRepositoryAdapter delegate;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;
    private final Supplier<UUID> idGenerator;

    public AuditingAiAnalysisResponseRepository(
            AiAnalysisResponseRepositoryAdapter delegate, AuditLogWriter auditLogWriter, Clock clock) {
        this.delegate = delegate;
        this.auditLogWriter = auditLogWriter;
        this.clock = clock;
        this.idGenerator = UUID::randomUUID;
    }

    @Override
    public AiAnalysisResponse save(AiAnalysisResponse response) {
        AiAnalysisResponse saved = delegate.save(response);
        boolean verified = saved.verificationStatus() != VerificationStatus.NOT_VERIFIED;
        auditLogWriter.record(new AuditLog(
                idGenerator.get(),
                AuditedEntity.AI_ANALYSIS_RESPONSE,
                saved.id(),
                saved.legalCaseId(),
                verified ? AuditAction.AI_ANSWER_VERIFIED : AuditAction.AI_ANSWER_RECORDED,
                saved.isFromLlm() ? AuditLog.AI_ACTOR : AuditLog.SYSTEM_ACTOR,
                payloadOf(saved),
                clock.instant()));
        return saved;
    }

    @Override
    public List<AiAnalysisResponse> findByLegalCaseId(UUID legalCaseId) {
        return delegate.findByLegalCaseId(legalCaseId);
    }

    @Override
    public Optional<AiAnalysisResponse> findByLegalCaseIdAndQuestionKey(UUID legalCaseId, QuestionKey questionKey) {
        return delegate.findByLegalCaseIdAndQuestionKey(legalCaseId, questionKey);
    }

    @Override
    public void deleteByLegalCaseId(UUID legalCaseId) {
        delegate.deleteByLegalCaseId(legalCaseId);
    }

    private static Map<String, String> payloadOf(AiAnalysisResponse response) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("questionKey", response.questionKey().name());
        payload.put("answerSource", response.answerSource().name());
        payload.put("confidenceScore", Double.toString(response.confidenceScore().value()));
        payload.put("citedChunks", Integer.toString(response.citedChunks().size()));
        payload.put("verificationStatus", response.verificationStatus().name());
        if (response.modelVersion() != null) {
            payload.put("modelVersion", response.modelVersion());
        }
        if (response.promptVersionId() != null) {
            payload.put("promptVersionId", response.promptVersionId().toString());
        }
        return payload;
    }
}
