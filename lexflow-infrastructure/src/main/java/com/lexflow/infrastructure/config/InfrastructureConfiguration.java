package com.lexflow.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Beans de apoio compartilhados pelos adapters de infraestrutura.
 *
 * <p>O agendamento é habilitado aqui por causa da expiração das chaves de idempotência (Prompt 17):
 * é a única tarefa periódica do sistema, e ela é manutenção — nada do fluxo de negócio depende dela.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class InfrastructureConfiguration {

    /**
     * Relógio único da aplicação, em UTC.
     *
     * <p>Injetar o relógio, em vez de chamar {@code Instant.now()} espalhado pelo código, é o que
     * permite fixar o horário nos testes e manter todos os carimbos de tempo na mesma referência.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
