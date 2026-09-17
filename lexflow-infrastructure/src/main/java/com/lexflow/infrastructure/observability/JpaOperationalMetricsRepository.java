package com.lexflow.infrastructure.observability;

import com.lexflow.application.metrics.AiHumanAgreement;
import com.lexflow.application.metrics.AiSuggestion;
import com.lexflow.application.metrics.OperationalMetricsRepository;
import com.lexflow.domain.decision.DecisionType;
import com.lexflow.domain.legalcase.LegalCaseType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Métricas agregadas lidas do banco (Prompt 18).
 *
 * <p>São consultas nativas porque agregação é o que o banco faz melhor: trazer as linhas para a
 * aplicação e somá-las em memória custaria muito mais e daria o mesmo número.
 *
 * <p>A concordância vem da visão {@code ai_human_agreement} (migration V10): ela existe justamente
 * para que a definição de "sugestão implícita" fique em um lugar só, e não espalhada em consultas.
 */
@Component
@Transactional(readOnly = true)
public class JpaOperationalMetricsRepository implements OperationalMetricsRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Map<String, Long> countCasesByStatus() {
        return toCountMap(entityManager
                .createNativeQuery("SELECT status, count(*) FROM legal_cases GROUP BY status ORDER BY status")
                .getResultList());
    }

    @Override
    public Map<String, Double> averageTimeToReviewSeconds() {
        // O instante em que a demanda chegou à revisão sai do próprio histórico de transições: é o
        // registro que a seção 4 garante existir para toda transição.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager
                .createNativeQuery(
                        """
                        SELECT c.case_type, avg(EXTRACT(EPOCH FROM (h.changed_at - c.created_at)))
                        FROM legal_cases c
                        JOIN legal_case_status_history h ON h.legal_case_id = c.id
                        WHERE h.new_status = 'PENDING_HUMAN_REVIEW'
                        GROUP BY c.case_type
                        ORDER BY c.case_type
                        """)
                .getResultList();
        Map<String, Double> averages = new LinkedHashMap<>();
        for (Object[] row : rows) {
            averages.put((String) row[0], toDouble(row[1]));
        }
        return averages;
    }

    @Override
    public Map<String, Long> countOpenAlertsByType() {
        return toCountMap(entityManager
                .createNativeQuery(
                        """
                        SELECT alert_type, count(*) FROM legal_case_alerts
                        WHERE resolved_at IS NULL
                        GROUP BY alert_type ORDER BY alert_type
                        """)
                .getResultList());
    }

    @Override
    public List<AiHumanAgreement> findAgreements(int limit) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager
                .createNativeQuery(
                        """
                        SELECT legal_case_id, case_type, decision_type, ai_suggestion, agreed,
                               answer_count, min_confidence, avg_confidence, failed_verifications,
                               open_alerts, decided_at
                        FROM ai_human_agreement
                        ORDER BY decided_at DESC
                        LIMIT :limit
                        """)
                .setParameter("limit", limit)
                .getResultList();

        return rows.stream()
                .map(row -> new AiHumanAgreement(
                        (UUID) row[0],
                        LegalCaseType.of((String) row[1]),
                        DecisionType.valueOf((String) row[2]),
                        AiSuggestion.valueOf((String) row[3]),
                        (Boolean) row[4],
                        ((Number) row[5]).intValue(),
                        toNullableDouble(row[6]),
                        toNullableDouble(row[7]),
                        ((Number) row[8]).intValue(),
                        ((Number) row[9]).intValue(),
                        toInstant(row[10])))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> toCountMap(List<?> resultList) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : (List<Object[]>) resultList) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private static double toDouble(Object value) {
        return value instanceof BigDecimal decimal ? decimal.doubleValue() : ((Number) value).doubleValue();
    }

    private static Double toNullableDouble(Object value) {
        return value == null ? null : toDouble(value);
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return value == null ? null : Instant.parse(value.toString());
    }
}
