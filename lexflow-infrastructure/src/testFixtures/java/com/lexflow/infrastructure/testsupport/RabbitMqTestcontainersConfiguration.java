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
 *   <li><strong>Retry curto.</strong> Em produção a janela de novas tentativas passa de dois minutos,
 *       para atravessar a queda de um provedor externo (Prompt 17); um teste que a respeitasse
 *       levaria esse tempo todo. Aqui são quatro tentativas em menos de um segundo — o suficiente
 *       para exercitar o mesmo mecanismo: a falha, a nova tentativa e, esgotadas as tentativas, a
 *       dead-letter. A duração das esperas é decisão de configuração, não de código.
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
            registry.add("spring.rabbitmq.listener.simple.retry.max-attempts", () -> 4);
            registry.add("spring.rabbitmq.listener.simple.retry.initial-interval", () -> "100ms");
            registry.add("spring.rabbitmq.listener.simple.retry.max-interval", () -> "300ms");
            registry.add("spring.rabbitmq.template.retry.max-attempts", () -> 2);
            registry.add("spring.rabbitmq.template.retry.initial-interval", () -> "50ms");
        };
    }
}
