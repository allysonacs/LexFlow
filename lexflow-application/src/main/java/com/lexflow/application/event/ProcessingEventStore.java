package com.lexflow.application.event;

import java.util.UUID;

/**
 * Porta que sustenta a idempotência do consumo de eventos (seção 11 da base de conhecimento).
 *
 * <p>A garantia vem da restrição de unicidade sobre {@code processing_events.idempotency_key}, e não
 * de uma consulta feita antes da gravação: é isso que impede duas réplicas do consumidor de
 * processarem a mesma mensagem quando a entrega é duplicada.
 *
 * <p>Existe separada de
 * {@link com.lexflow.application.legalcase.LegalCaseIngestionIdempotencyStore}, embora as duas usem a
 * mesma tabela, porque os ciclos de vida são diferentes: a da ingestão protege um endpoint HTTP, onde
 * uma chave concluída significa "devolva a mesma resposta" e não existe estado de falha; esta protege
 * o consumo de fila, onde uma falha precisa ser registrada para que a próxima entrega possa tentar de
 * novo. Unificá-las obrigaria uma das duas a carregar conceitos da outra.
 */
public interface ProcessingEventStore {

    /**
     * Tenta assumir o processamento do evento.
     *
     * <p>Um evento que falhou antes pode ser reservado de novo: é isso que permite que a reentrega da
     * mensagem tente outra vez.
     *
     * @param idempotencyKey chave que acompanha a mensagem
     * @param eventType tipo do evento, gravado para diagnóstico
     * @param aggregateId identificador do agregado a que o evento se refere
     */
    ProcessingReservation reserve(String idempotencyKey, String eventType, UUID aggregateId);

    /** Marca o evento como processado com sucesso; a partir daqui toda reentrega é ignorada. */
    void markProcessed(String idempotencyKey);

    /**
     * Marca o evento como falho, liberando-o para uma nova tentativa.
     *
     * <p>Deve rodar fora da transação do processamento: se participasse dela, o rollback apagaria o
     * próprio registro da falha.
     */
    void markFailed(String idempotencyKey);
}
