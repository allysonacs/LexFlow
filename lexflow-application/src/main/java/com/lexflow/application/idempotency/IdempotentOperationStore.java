package com.lexflow.application.idempotency;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta que sustenta a idempotência dos endpoints de escrita (seção 11).
 *
 * <p>É uma porta só para todos os endpoints: a garantia exigida é sempre a mesma — um índice único
 * sobre a chave —, e duplicá-la por caso de uso criaria vários lugares para manter a mesma regra.
 * O que separa um endpoint do outro é o {@link IdempotencyNamespace}.
 */
public interface IdempotentOperationStore {

    /**
     * Reserva a chave para a operação que está prestes a acontecer.
     *
     * @param aggregateId agregado que a operação vai afetar
     * @return vazio quando a reserva foi feita agora e a operação pode seguir; o registro existente
     *     quando a chave já estava em uso — caso em que nada novo deve ser criado
     */
    Optional<IdempotentOperation> reserve(IdempotencyNamespace namespace, String idempotencyKey, UUID aggregateId);

    /** Marca a operação como concluída, liberando as próximas requisições para a resposta repetida. */
    void markCompleted(IdempotencyNamespace namespace, String idempotencyKey);
}
