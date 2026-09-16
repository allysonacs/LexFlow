package com.lexflow.infrastructure.persistence.adapter;

import com.lexflow.application.transaction.TransactionRunner;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementação de {@link TransactionRunner} sobre o gerenciador de transações do Spring.
 *
 * <p>É o único ponto em que a demarcação de transação aparece: os casos de uso pedem "faça isto em
 * uma transação" e continuam sem saber o que é uma transação.
 */
@Component
public class SpringTransactionRunner implements TransactionRunner {

    @Override
    @Transactional
    public <T> T inTransaction(Supplier<T> action) {
        return action.get();
    }

    @Override
    @Transactional
    public void runInTransaction(Runnable action) {
        action.run();
    }
}
