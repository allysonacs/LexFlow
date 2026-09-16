package com.lexflow.infrastructure.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Configuração base do Testcontainers para testes que precisam do broker.
 *
 * <p>Além de subir o RabbitMQ, ajusta duas coisas para tornar os testes de fila viáveis:
 *
 * <ul>
 *   <li><strong>Retry curto.</strong> Em produção o backoff vai de 1s a 10s, o que faria um teste de
 *       dead-letter levar dezenas de segundos. Aqui são duas tentativas quase imediatas — o que se
 *       quer verificar é que a mensagem acaba na dead-letter, não a duração das esperas.
 *   <li><strong>Consumidor parado na inicialização.</strong> Sem isso, o consumo em segundo plano
 *       competiria com as asserções de todo teste que cria uma demanda, e o status observado
 *       dependeria de quem chegasse primeiro. Cada teste de fila inicia o listener no momento em que
 *       quer que ele comece a trabalhar.
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class RabbitMqTestcontainersConfiguration {

    /** Mesma linha de versão usada no {@code compose.yaml}. */
    public static final DockerImageName RABBITMQ_IMAGE = DockerImageName.parse("rabbitmq:4.1-management-alpine")
            .asCompatibleSubstituteFor("rabbitmq");

    @Bean
    @ServiceConnection
    public RabbitMQContainer rabbitMqContainer() {
        return new RabbitMQContainer(RABBITMQ_IMAGE);
    }

    @Bean
    public DynamicPropertyRegistrar rabbitMqTestProperties() {
        return registry -> {
            registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> false);
            registry.add("spring.rabbitmq.listener.simple.retry.max-attempts", () -> 2);
            registry.add("spring.rabbitmq.listener.simple.retry.initial-interval", () -> "50ms");
            registry.add("spring.rabbitmq.listener.simple.retry.max-interval", () -> "100ms");
            registry.add("spring.rabbitmq.template.retry.max-attempts", () -> 2);
            registry.add("spring.rabbitmq.template.retry.initial-interval", () -> "50ms");
        };
    }
}
