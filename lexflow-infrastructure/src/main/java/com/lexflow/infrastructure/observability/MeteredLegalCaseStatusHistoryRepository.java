package com.lexflow.infrastructure.observability;

import com.lexflow.application.legalcase.LegalCaseRepository;
import com.lexflow.application.legalcase.LegalCaseStatusHistoryEntry;
import com.lexflow.application.legalcase.LegalCaseStatusHistoryRepository;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.infrastructure.audit.AuditingLegalCaseStatusHistoryRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Mede o fluxo do pipeline a partir das transições de status (Prompt 18, item 2).
 *
 * <p>Fica na mesma costura da auditoria e pelo mesmo motivo: a seção 4 garante que <em>toda</em>
 * transição gera uma linha de histórico, então instrumentar a gravação do histórico cobre o pipeline
 * inteiro sem que nenhum caso de uso precise conhecer métricas. A cadeia é
 * {@code Metered → Auditing → JPA}.
 *
 * <p>Como a auditoria, ela observa: uma falha ao medir não pode desfazer a transição que estava sendo
 * medida.
 */
@Component
@Primary
public class MeteredLegalCaseStatusHistoryRepository implements LegalCaseStatusHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(MeteredLegalCaseStatusHistoryRepository.class);

    private final AuditingLegalCaseStatusHistoryRepository delegate;
    private final LegalCaseRepository legalCaseRepository;
    private final PipelineMetrics metrics;

    public MeteredLegalCaseStatusHistoryRepository(
            AuditingLegalCaseStatusHistoryRepository delegate,
            LegalCaseRepository legalCaseRepository,
            PipelineMetrics metrics) {
        this.delegate = delegate;
        this.legalCaseRepository = legalCaseRepository;
        this.metrics = metrics;
    }

    @Override
    public void save(LegalCaseStatusHistoryEntry entry) {
        delegate.save(entry);
        try {
            measure(entry);
        } catch (RuntimeException e) {
            log.warn("Falha ao medir a transição da demanda {}", entry.legalCaseId(), e);
        }
    }

    @Override
    public List<LegalCaseStatusHistoryEntry> findByLegalCaseId(UUID legalCaseId) {
        return delegate.findByLegalCaseId(legalCaseId);
    }

    /**
     * A demanda é lida apenas para saber o tipo e o instante de criação.
     *
     * <p>É uma consulta a mais por transição, e ela se paga: sem o tipo, a métrica de tempo viraria um
     * número único que mistura demandas de naturezas muito diferentes e não responde nada.
     */
    private void measure(LegalCaseStatusHistoryEntry entry) {
        LegalCase legalCase = legalCaseRepository.findById(entry.legalCaseId()).orElse(null);
        if (legalCase == null) {
            return;
        }
        metrics.recordTransition(legalCase.caseType(), entry.previousStatus(), entry.newStatus());
        if (entry.newStatus() == LegalCaseStatus.PENDING_HUMAN_REVIEW) {
            metrics.recordTimeToReview(
                    legalCase.caseType(), Duration.between(legalCase.createdAt(), entry.changedAt()));
        }
    }
}
