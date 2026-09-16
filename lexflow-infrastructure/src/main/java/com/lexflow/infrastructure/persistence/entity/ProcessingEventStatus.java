package com.lexflow.infrastructure.persistence.entity;

/**
 * Situação de um evento assíncrono na tabela {@code processing_events}.
 *
 * <p>É um conceito de infraestrutura, não de domínio: serve ao controle de idempotência do consumo de
 * fila (Prompt 07), não a nenhuma regra jurídica.
 */
public enum ProcessingEventStatus {

    /** Evento reservado por um worker, com processamento em andamento. */
    IN_PROGRESS,

    /** Processamento concluído com sucesso: uma nova entrega da mesma mensagem deve ser ignorada. */
    PROCESSED,

    /** Processamento falhou de forma definitiva; a mensagem segue para a dead-letter. */
    FAILED
}
