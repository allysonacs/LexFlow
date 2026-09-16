package com.lexflow.application.exception;

/**
 * Lançada quando chega uma requisição com uma {@code Idempotency-Key} que já está reservada, mas cuja
 * ingestão ainda não foi concluída.
 *
 * <p>Acontece em dois casos: duas requisições iguais em paralelo, ou uma tentativa anterior que
 * falhou no meio do caminho. Nos dois, devolver 201 seria mentira e criar uma segunda demanda
 * duplicaria o trabalho — daí o conflito explícito, para que o cliente decida entre reenviar com
 * outra chave ou consultar a demanda original. A liberação automática de chaves presas é tema do
 * Prompt 17 (resiliência e idempotência).
 */
public class IdempotentRequestInProgressException extends ApplicationException {

    private final String idempotencyKey;

    public IdempotentRequestInProgressException(String idempotencyKey) {
        super("Já existe uma ingestão em andamento para a chave de idempotência '%s'".formatted(idempotencyKey));
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
