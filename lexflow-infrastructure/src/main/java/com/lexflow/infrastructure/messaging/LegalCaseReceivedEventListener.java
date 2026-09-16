package com.lexflow.infrastructure.messaging;

import com.lexflow.application.legalcase.LegalCaseProcessingOutcome;
import com.lexflow.application.legalcase.LegalCaseReceivedEvent;
import com.lexflow.application.legalcase.ProcessLegalCaseReceivedEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor da fila de demandas recebidas.
 *
 * <p>É de propósito uma casca fina: desserializa, delega ao caso de uso e registra o desfecho. Toda a
 * decisão — reservar o evento, avançar o status, tratar a duplicidade — está em
 * {@link ProcessLegalCaseReceivedEventService}, que não conhece RabbitMQ e pode ser testado sem
 * broker nenhum.
 *
 * <p><strong>Falhar é intencional.</strong> Quando o caso de uso lança, a exceção sobe: é ela que
 * aciona o retry com backoff configurado em {@code spring.rabbitmq.listener.simple.retry} e, esgotadas
 * as tentativas, faz o broker mandar a mensagem para a dead-letter. Capturar a exceção aqui daria um
 * "processado com sucesso" falso e perderia a mensagem em silêncio.
 *
 * <p>O identificador do listener existe para que os testes possam iniciá-lo sob demanda, mantendo
 * determinístico o que de outro modo seria uma corrida com o consumo em segundo plano.
 */
@Component
public class LegalCaseReceivedEventListener {

    /** Identificador do container deste listener, usado pelos testes de integração. */
    public static final String LISTENER_ID = "legalCaseReceivedListener";

    private static final Logger log = LoggerFactory.getLogger(LegalCaseReceivedEventListener.class);

    private final ProcessLegalCaseReceivedEventService processLegalCaseReceivedEventService;

    public LegalCaseReceivedEventListener(ProcessLegalCaseReceivedEventService processLegalCaseReceivedEventService) {
        this.processLegalCaseReceivedEventService = processLegalCaseReceivedEventService;
    }

    @RabbitListener(id = LISTENER_ID, queues = RabbitMqConfiguration.LEGAL_CASE_RECEIVED_QUEUE)
    public void onLegalCaseReceived(LegalCaseReceivedEvent event) {
        LegalCaseProcessingOutcome outcome = processLegalCaseReceivedEventService.process(event);

        if (outcome.isSkipped()) {
            // Entrega repetida não é erro: a seção 11 manda registrar e seguir em frente.
            log.info(
                    "Evento {} descartado ({}): demanda={} chave={}",
                    LegalCaseReceivedEvent.EVENT_TYPE,
                    outcome,
                    event.legalCaseId(),
                    event.idempotencyKey());
            return;
        }

        log.info(
                "Evento {} processado: demanda={} chave={}",
                LegalCaseReceivedEvent.EVENT_TYPE,
                event.legalCaseId(),
                event.idempotencyKey());
    }
}
