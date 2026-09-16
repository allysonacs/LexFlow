package com.lexflow.application.transaction;

import java.util.function.Supplier;

/**
 * Porta que delimita uma transação sem que a camada de aplicação conheça o gerenciador de
 * transações.
 *
 * <p>Existe porque a ingestão precisa gravar a demanda, o registro de histórico e os metadados dos
 * documentos como uma unidade só: uma demanda gravada sem a sua linha de histórico quebraria a
 * rastreabilidade exigida pela seção 4 da base de conhecimento. Anotar o caso de uso com
 * {@code @Transactional} resolveria o problema, mas colocaria o Spring dentro de
 * {@code lexflow-application}, o que a seção 7 proíbe.
 */
public interface TransactionRunner {

    /** Executa a ação em uma transação e devolve o seu resultado. */
    <T> T inTransaction(Supplier<T> action);

    /** Executa a ação em uma transação, quando não há resultado a devolver. */
    void runInTransaction(Runnable action);
}
