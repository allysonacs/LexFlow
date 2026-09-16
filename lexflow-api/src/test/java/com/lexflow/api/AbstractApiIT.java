package com.lexflow.api;

import com.lexflow.infrastructure.testsupport.MinioTestcontainersConfiguration;
import com.lexflow.infrastructure.testsupport.PostgresTestcontainersConfiguration;
import com.lexflow.infrastructure.testsupport.RabbitMqTestcontainersConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base dos testes de integração da API: a aplicação inteira no ar, com PostgreSQL, MinIO e RabbitMQ
 * reais.
 *
 * <p>Todas as classes compartilham esta mesma configuração de propósito — assim o Spring reaproveita
 * o contexto e os containers entre elas, em vez de subir um conjunto novo por classe de teste.
 *
 * <p>O consumidor da fila sobe parado (ver {@link RabbitMqTestcontainersConfiguration}); quem quiser
 * exercitar o processamento assíncrono o inicia explicitamente.
 *
 * <p>Requer Docker em execução.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
    PostgresTestcontainersConfiguration.class,
    MinioTestcontainersConfiguration.class,
    RabbitMqTestcontainersConfiguration.class
})
public abstract class AbstractApiIT {}
