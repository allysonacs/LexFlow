package com.lexflow.infrastructure.messaging;

import com.lexflow.application.legalcase.LegalCaseReceivedEvent;
import com.lexflow.application.legalcase.LegalCaseReceivedEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica o evento de demanda recebida no RabbitMQ.
 *
 * <p>A chave de idempotência viaja também no cabeçalho {@code message-id}: além de estar no corpo, ela
 * fica visível para quem inspecionar a fila e para qualquer mecanismo do broker que trabalhe com
 * identidade de mensagem, sem precisar desserializar o payload.
 */
@Component
public class RabbitLegalCaseReceivedEventPublisher implements LegalCaseReceivedEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitLegalCaseReceivedEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public RabbitLegalCaseReceivedEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(LegalCaseReceivedEvent event) {
        rabbitTemplate.convertAndSend(
                RabbitMqConfiguration.EVENTS_EXCHANGE,
                RabbitMqConfiguration.LEGAL_CASE_RECEIVED_ROUTING_KEY,
                event,
                message -> {
                    message.getMessageProperties().setMessageId(event.idempotencyKey());
                    return message;
                });

        // Metadados apenas: nada do conteúdo dos documentos vai para o log (seção 12).
        log.info(
                "Evento {} publicado: demanda={} tipo={} prioridade={} documentos={} chave={}",
                LegalCaseReceivedEvent.EVENT_TYPE,
                event.legalCaseId(),
                event.caseType(),
                event.priority(),
                event.documentCount(),
                event.idempotencyKey());
    }
}
