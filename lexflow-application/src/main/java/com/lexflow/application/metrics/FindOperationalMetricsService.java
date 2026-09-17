package com.lexflow.application.metrics;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Monta os números apresentados pela API de métricas (Prompt 18, itens 3 e 5).
 *
 * <p>O cálculo da concordância fica aqui, e não no banco, por um motivo: a definição de "concordar" é
 * regra de negócio, e ela precisa estar escrita em um lugar que se leia e se teste sem subir um
 * PostgreSQL. A visão do banco entrega os fatos; a leitura deles é desta classe.
 */
public class FindOperationalMetricsService {

    /** Quantas discordâncias acompanham o resumo, para quem quiser investigar sem outra consulta. */
    private static final int DISAGREEMENT_SAMPLES = 20;

    /** Teto de demandas lidas para o resumo; acima disso, o painel deveria vir de um agregado. */
    private static final int MAX_CASES = 5_000;

    private final OperationalMetricsRepository repository;
    private final Clock clock;

    public FindOperationalMetricsService(OperationalMetricsRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository não pode ser nulo");
        this.clock = Objects.requireNonNull(clock, "clock não pode ser nulo");
    }

    /** Concordância entre a sugestão implícita da IA e a decisão humana. */
    public AiHumanAgreementSummary agreement() {
        List<AiHumanAgreement> agreements = repository.findAgreements(MAX_CASES);

        long conclusive = agreements.stream().filter(item -> item.agreed() != null).count();
        long agreed = agreements.stream().filter(item -> Boolean.TRUE.equals(item.agreed())).count();
        long disagreed = conclusive - agreed;

        Map<String, long[]> perType = new LinkedHashMap<>();
        for (AiHumanAgreement item : agreements) {
            if (item.agreed() == null) {
                continue;
            }
            long[] counters = perType.computeIfAbsent(item.caseType().name(), key -> new long[2]);
            counters[0]++;
            if (Boolean.TRUE.equals(item.agreed())) {
                counters[1]++;
            }
        }
        Map<String, Double> byCaseType = new LinkedHashMap<>();
        perType.forEach((type, counters) -> byCaseType.put(type, (double) counters[1] / counters[0]));

        List<AiHumanAgreement> samples = new ArrayList<>();
        for (AiHumanAgreement item : agreements) {
            if (Boolean.FALSE.equals(item.agreed()) && samples.size() < DISAGREEMENT_SAMPLES) {
                samples.add(item);
            }
        }

        return new AiHumanAgreementSummary(
                agreements.size(),
                conclusive,
                agreed,
                disagreed,
                // Nulo, e não zero, quando ainda não há caso conclusivo: zero seria lido como "a IA
                // erra sempre", que é uma afirmação bem diferente de "ainda não dá para dizer".
                conclusive == 0 ? null : (double) agreed / conclusive,
                byCaseType,
                samples);
    }

    /** Dados agregados para um painel. */
    public OperationalDashboard dashboard() {
        return new OperationalDashboard(
                clock.instant(),
                repository.countCasesByStatus(),
                repository.averageTimeToReviewSeconds(),
                repository.countOpenAlertsByType(),
                agreement());
    }
}
