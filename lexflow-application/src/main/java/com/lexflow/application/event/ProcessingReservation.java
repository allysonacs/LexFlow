package com.lexflow.application.event;

/**
 * Resultado da tentativa de reservar um evento para processamento.
 *
 * <p>É um enum, e não um booleano, porque as três situações pedem reações diferentes de quem consome
 * a fila — e confundir "já foi processado" com "está sendo processado agora" é justamente o tipo de
 * engano que leva ao processamento em duplicidade.
 */
public enum ProcessingReservation {

    /** A reserva foi feita agora: este consumidor é o dono do processamento e deve seguir. */
    RESERVED,

    /**
     * O evento já foi processado com sucesso antes. A mensagem deve ser descartada com um log, e não
     * tratada como erro — é exatamente o que manda a seção 11 da base de conhecimento.
     */
    ALREADY_PROCESSED,

    /**
     * Outra réplica reservou o evento e ainda não terminou. Descartar esta entrega evita processar a
     * mesma demanda duas vezes em paralelo.
     *
     * <p>Uma reserva pode ficar presa neste estado se o processo morrer abruptamente, sem conseguir
     * marcar a falha. Liberar reservas antigas é tema do Prompt 17 (resiliência e idempotência).
     */
    IN_PROGRESS_ELSEWHERE
}
