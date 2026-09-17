package com.lexflow.infrastructure.messaging;

import com.lexflow.application.review.DecisionRegisteredEvent;
import com.lexflow.application.review.DecisionRegisteredEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica o evento de decisão registrada no RabbitMQ (Prompt 15, item 5).
 *
 * <p>Como na publicação da demanda recebida, a chave de idempotência viaja também no
 * {@code message-id}, para ficar visível a quem inspecionar a fila sem desserializar o payload.
 */
@Component
public class RabbitDecisionRegisteredEventPublisher implements DecisionRegisteredEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitDecisionRegisteredEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public RabbitDecisionRegisteredEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(DecisionRegisteredEvent event) {
        rabbitTemplate.convertAndSend(
                RabbitMqConfiguration.EVENTS_EXCHANGE,
                RabbitMqConfiguration.DECISION_REGISTERED_ROUTING_KEY,
                event,
                message -> {
                    message.getMessageProperties().setMessageId(event.idempotencyKey());
                    return message;
                });

        // Metadados apenas: o comentário da decisão não vai para o log (seção 12).
        log.info(
                "Evento {} publicado: demanda={} decisão={} status={} por={} chave={}",
                DecisionRegisteredEvent.EVENT_TYPE,
                event.legalCaseId(),
                event.decisionType(),
                event.resultingStatus(),
                event.decidedBy(),
                event.idempotencyKey());
    }
}
