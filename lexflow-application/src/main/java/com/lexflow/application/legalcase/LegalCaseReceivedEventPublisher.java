package com.lexflow.application.legalcase;

/**
 * Porta de saída que desacopla a ingestão do processamento.
 *
 * <p>É o ponto de extensão previsto pelo Prompt 05: a ingestão termina publicando aqui e devolve a
 * resposta. Nenhuma classificação e nenhuma chamada de IA acontecem de forma síncrona na requisição.
 * O adapter real — Kafka ou RabbitMQ — entra no Prompt 07; até lá a infraestrutura fornece uma
 * implementação que apenas registra o evento no log.
 */
public interface LegalCaseReceivedEventPublisher {

    /** Publica o evento. É chamado somente depois que a demanda já está gravada. */
    void publish(LegalCaseReceivedEvent event);
}
