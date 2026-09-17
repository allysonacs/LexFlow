package com.lexflow.application.legalcase;

/**
 * O que aconteceu com um evento entregue ao consumidor.
 *
 * <p>O caso de uso devolve o desfecho em vez de registrá-lo no log porque {@code lexflow-application}
 * não depende de nenhum framework, nem sequer de uma fachada de log. Quem consome a fila conhece o
 * contexto da mensagem e é quem deve registrá-la.
 */
public enum LegalCaseProcessingOutcome {

    /** A demanda percorreu as etapas do processamento e o evento foi marcado como processado. */
    PROCESSED,

    /** O evento já havia sido processado antes; nada foi feito, e isso não é erro. */
    SKIPPED_ALREADY_PROCESSED,

    /** Outra réplica está processando este mesmo evento; esta entrega foi descartada. */
    SKIPPED_IN_PROGRESS;

    /** Indica se a entrega deve ser confirmada sem que nada tenha sido feito. */
    public boolean isSkipped() {
        return this != PROCESSED;
    }
}
