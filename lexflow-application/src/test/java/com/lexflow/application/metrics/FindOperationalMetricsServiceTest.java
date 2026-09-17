package com.lexflow.application.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.lexflow.domain.decision.DecisionType;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Leitura da métrica de concordância entre a IA e o revisor humano (Prompt 18, item 3).
 *
 * <p>O cálculo mora na aplicação, e não no banco, justamente para poder ser lido e testado assim: a
 * definição de "concordar" é regra de negócio.
 */
class FindOperationalMetricsServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    @Test
    @DisplayName("critério de aceite: a concordância é calculável a partir das demandas decididas")
    void shouldComputeAgreementRate() {
        FindOperationalMetricsService service = serviceWith(
                agreement(LegalCaseType.CONTRACT_SIGNING, DecisionType.APPROVED, AiSuggestion.FAVORABLE, true),
                agreement(LegalCaseType.CONTRACT_SIGNING, DecisionType.REJECTED, AiSuggestion.UNFAVORABLE, true),
                agreement(LegalCaseType.CONTRACT_SIGNING, DecisionType.APPROVED, AiSuggestion.UNFAVORABLE, false),
                agreement(LegalCaseType.SETTLEMENT_PAYMENT, DecisionType.APPROVED, AiSuggestion.FAVORABLE, true));

        AiHumanAgreementSummary summary = service.agreement();

        assertThat(summary.decidedCases()).isEqualTo(4);
        assertThat(summary.conclusiveCases()).isEqualTo(4);
        assertThat(summary.agreements()).isEqualTo(3);
        assertThat(summary.disagreements()).isEqualTo(1);
        assertThat(summary.agreementRate()).isCloseTo(0.75, within(1e-9));
        // A concordância por tipo é onde uma queda aparece antes de aparecer no número geral.
        assertThat(summary.byCaseType())
                .containsEntry("CONTRACT_SIGNING", 2.0 / 3)
                .containsEntry("SETTLEMENT_PAYMENT", 1.0);
        assertThat(summary.disagreementSamples())
                .singleElement()
                .satisfies(sample -> assertThat(sample.decisionType()).isEqualTo(DecisionType.APPROVED));
    }

    @Test
    @DisplayName("demandas sem leitura conclusiva não contam como acerto nem como erro")
    void shouldIgnoreInconclusiveCases() {
        FindOperationalMetricsService service = serviceWith(
                agreement(LegalCaseType.CONTRACT_SIGNING, DecisionType.APPROVED, AiSuggestion.FAVORABLE, true),
                agreement(LegalCaseType.CONTRACT_SIGNING, DecisionType.APPROVED, AiSuggestion.INCONCLUSIVE, null));

        AiHumanAgreementSummary summary = service.agreement();

        assertThat(summary.decidedCases()).isEqualTo(2);
        assertThat(summary.conclusiveCases()).isEqualTo(1);
        assertThat(summary.agreementRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("sem nenhuma demanda conclusiva, a taxa é nula — e não zero")
    void shouldReturnNullRateWhenThereIsNothingToMeasure() {
        // Zero seria lido como "a IA erra sempre", que é bem diferente de "ainda não dá para dizer".
        assertThat(serviceWith().agreement().agreementRate()).isNull();
        assertThat(serviceWith(agreement(
                                LegalCaseType.CONTRACT_SIGNING,
                                DecisionType.APPROVED,
                                AiSuggestion.INCONCLUSIVE,
                                null))
                        .agreement()
                        .agreementRate())
                .isNull();
    }

    @Test
    @DisplayName("o painel junta fila, tempo por tipo, alertas e concordância")
    void shouldAssembleTheDashboard() {
        FindOperationalMetricsService service = serviceWith(
                agreement(LegalCaseType.CONTRACT_SIGNING, DecisionType.APPROVED, AiSuggestion.FAVORABLE, true));

        OperationalDashboard dashboard = service.dashboard();

        assertThat(dashboard.generatedAt()).isEqualTo(NOW);
        assertThat(dashboard.casesByStatus()).containsEntry("PENDING_HUMAN_REVIEW", 3L);
        assertThat(dashboard.averageTimeToReviewSeconds()).containsEntry("CONTRACT_SIGNING", 42.0);
        assertThat(dashboard.openAlertsByType()).containsEntry("AI_ANALYSIS_INVALID_OUTPUT", 1L);
        assertThat(dashboard.agreement().agreementRate()).isEqualTo(1.0);
    }

    private FindOperationalMetricsService serviceWith(AiHumanAgreement... agreements) {
        List<AiHumanAgreement> rows = List.of(agreements);
        OperationalMetricsRepository repository = new OperationalMetricsRepository() {

            @Override
            public Map<String, Long> countCasesByStatus() {
                return Map.of("PENDING_HUMAN_REVIEW", 3L, "APPROVED", 1L);
            }

            @Override
            public Map<String, Double> averageTimeToReviewSeconds() {
                return Map.of("CONTRACT_SIGNING", 42.0);
            }

            @Override
            public Map<String, Long> countOpenAlertsByType() {
                return Map.of("AI_ANALYSIS_INVALID_OUTPUT", 1L);
            }

            @Override
            public List<AiHumanAgreement> findAgreements(int limit) {
                return new ArrayList<>(rows);
            }
        };
        return new FindOperationalMetricsService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AiHumanAgreement agreement(
            LegalCaseType caseType, DecisionType decisionType, AiSuggestion suggestion, Boolean agreed) {
        return new AiHumanAgreement(
                UUID.randomUUID(), caseType, decisionType, suggestion, agreed, 3, 0.8, 0.85, 0, 0, NOW);
    }
}
