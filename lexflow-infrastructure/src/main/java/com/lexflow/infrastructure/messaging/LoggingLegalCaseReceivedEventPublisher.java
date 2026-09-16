package com.lexflow.infrastructure.messaging;

import com.lexflow.application.legalcase.LegalCaseReceivedEvent;
import com.lexflow.application.legalcase.LegalCaseReceivedEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implementação provisória de {@link LegalCaseReceivedEventPublisher}, válida apenas até o Prompt 07.
 *
 * <p>Registra o evento no log em vez de publicá-lo em Kafka ou RabbitMQ. Serve para que a ingestão já
 * termine no ponto de extensão correto: o Prompt 07 troca esta classe pelo adapter de fila e nenhum
 * caso de uso muda.
 */
@Component
public class LoggingLegalCaseReceivedEventPublisher implements LegalCaseReceivedEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingLegalCaseReceivedEventPublisher.class);

    @Override
    public void publish(LegalCaseReceivedEvent event) {
        log.info(
                "Fila ainda não configurada (Prompt 07): evento {} da demanda {} ({}, prioridade {}, {} documento(s)) não foi publicado; chave de idempotência {}",
                LegalCaseReceivedEvent.EVENT_TYPE,
                event.legalCaseId(),
                event.caseType(),
                event.priority(),
                event.documentCount(),
                event.idempotencyKey());
    }
}
