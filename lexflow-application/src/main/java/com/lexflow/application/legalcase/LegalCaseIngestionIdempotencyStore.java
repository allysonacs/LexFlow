package com.lexflow.application.legalcase;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta que sustenta a idempotência do endpoint de ingestão (seção 11 da base de conhecimento).
 *
 * <p>A garantia tem de vir do banco, através de uma restrição de unicidade, e não de uma consulta
 * feita antes da gravação: só assim duas réplicas da API que recebam a mesma chave ao mesmo tempo
 * deixam de criar duas demandas.
 */
public interface LegalCaseIngestionIdempotencyStore {

    /**
     * Reserva a chave para a demanda que está prestes a ser criada.
     *
     * @return vazio quando a reserva foi feita agora e a ingestão pode seguir; o registro existente
     *     quando a chave já estava em uso — caso em que nenhuma demanda nova deve ser criada
     */
    Optional<IdempotentIngestion> reserve(String idempotencyKey, UUID legalCaseId);

    /** Marca a ingestão como concluída, liberando as próximas requisições para a resposta repetida. */
    void markCompleted(String idempotencyKey);
}
