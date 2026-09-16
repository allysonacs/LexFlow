package com.lexflow.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans de apoio compartilhados pelos adapters de infraestrutura. */
@Configuration(proxyBeanMethods = false)
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
