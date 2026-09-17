package com.lexflow.infrastructure.observability;

import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseType;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.lexflow.infrastructure.messaging.RabbitMqConfiguration;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Métricas do pipeline: tamanho das filas e tempo de processamento por tipo de demanda
 * (Prompt 18, item 2).
 *
 * <p><strong>Tamanho de fila é medido, não contado.</strong> O valor vem do próprio broker, em uma
 * consulta periódica: contar mensagens na aplicação daria um número que só vale para uma réplica.
 * A consulta é periódica, e não a cada coleta, para que um painel com muitos leitores não passe a
 * martelar o broker.
 *
 * <p><strong>Tempo de processamento é da demanda, não da mensagem.</strong> Mede-se da criação até a
 * chegada à revisão humana — é esse o número que responde "quanto tempo uma demanda leva para ficar
 * pronta para uma pessoa", e ele inclui esperas em fila, novas tentativas e tudo o mais.
 */
@Component
public class PipelineMetrics {

    /** Tempo da criação da demanda até ela ficar pronta para a revisão humana. */
    public static final String TIME_TO_REVIEW = "lexflow.legal_case.time_to_review";

    /** Mensagens aguardando consumo em cada fila. */
    public static final String QUEUE_DEPTH = "lexflow.queue.depth";

    /** Transições de status, para acompanhar o fluxo do pipeline. */
    public static final String STATUS_TRANSITIONS = "lexflow.legal_case.status_transitions";

    private static final Logger log = LoggerFactory.getLogger(PipelineMetrics.class);

    private final MeterRegistry meterRegistry;
    private final ObjectProvider<AmqpAdmin> amqpAdmin;
    private final AtomicInteger legalCaseQueueDepth = new AtomicInteger();
    private final AtomicInteger legalCaseDeadLetterDepth = new AtomicInteger();
    private final AtomicInteger decisionQueueDepth = new AtomicInteger();

    /**
     * @param amqpAdmin opcional: em um contexto sem broker — um teste enxuto, por exemplo — as demais
     *     métricas continuam valendo e o tamanho das filas sai como -1
     */
    public PipelineMetrics(MeterRegistry meterRegistry, ObjectProvider<AmqpAdmin> amqpAdmin) {
        this.meterRegistry = meterRegistry;
        this.amqpAdmin = amqpAdmin;
        registerQueueGauge(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_QUEUE, legalCaseQueueDepth);
        registerQueueGauge(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_DLQ, legalCaseDeadLetterDepth);
        registerQueueGauge(RabbitMqConfiguration.DECISION_REGISTERED_QUEUE, decisionQueueDepth);
    }

    /** Registra uma transição de status, com o tipo da demanda. */
    public void recordTransition(LegalCaseType caseType, LegalCaseStatus previousStatus, LegalCaseStatus newStatus) {
        meterRegistry
                .counter(
                        STATUS_TRANSITIONS,
                        "case_type", caseType.name(),
                        "from", previousStatus == null ? "none" : previousStatus.name(),
                        "to", newStatus.name())
                .increment();
    }

    /** Registra quanto tempo a demanda levou da criação até a revisão humana. */
    public void recordTimeToReview(LegalCaseType caseType, Duration duration) {
        Timer.builder(TIME_TO_REVIEW)
                .description("Tempo da criação da demanda até ela ficar pronta para a revisão humana")
                .tag("case_type", caseType.name())
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(duration);
    }

    /** Atualiza o tamanho das filas a partir do broker. */
    @Scheduled(fixedDelayString = "${lexflow.metrics.queue-depth-interval:15s}")
    public void refreshQueueDepth() {
        legalCaseQueueDepth.set(depthOf(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_QUEUE));
        legalCaseDeadLetterDepth.set(depthOf(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_DLQ));
        decisionQueueDepth.set(depthOf(RabbitMqConfiguration.DECISION_REGISTERED_QUEUE));
    }

    private void registerQueueGauge(String queue, AtomicInteger holder) {
        Gauge.builder(QUEUE_DEPTH, holder, AtomicInteger::get)
                .description("Mensagens aguardando consumo na fila")
                .tag("queue", queue)
                .register(meterRegistry);
    }

    /** Broker fora do ar não pode derrubar a coleta: a métrica fica em -1, que é um sinal em si. */
    private int depthOf(String queue) {
        AmqpAdmin admin = amqpAdmin.getIfAvailable();
        if (admin == null) {
            return -1;
        }
        try {
            QueueInformation info = admin.getQueueInfo(queue);
            return info == null ? -1 : info.getMessageCount();
        } catch (RuntimeException e) {
            log.debug("Não foi possível ler o tamanho da fila {}", queue, e);
            return -1;
        }
    }
}
