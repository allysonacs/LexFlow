package com.lexflow.application.review;

/**
 * Porta de saída do evento de decisão registrada.
 *
 * <p>A publicação acontece depois que a decisão já está gravada: notificação e auditoria (Prompts 16
 * em diante) são consequências da decisão, não parte dela.
 */
public interface DecisionRegisteredEventPublisher {

    void publish(DecisionRegisteredEvent event);
}
