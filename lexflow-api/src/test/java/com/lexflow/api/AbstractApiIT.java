package com.lexflow.api;

import com.lexflow.api.support.StubEmbeddingClientConfiguration;
import com.lexflow.api.support.StubLlmClientConfiguration;
import com.lexflow.infrastructure.testsupport.MinioTestcontainersConfiguration;
import com.lexflow.infrastructure.testsupport.PostgresTestcontainersConfiguration;
import com.lexflow.infrastructure.testsupport.RabbitMqTestcontainersConfiguration;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
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
 * <p>O LLM é sempre o {@link com.lexflow.api.support.StubLlmClient} e os embeddings vêm sempre do
 * {@link com.lexflow.infrastructure.testsupport.LexicalEmbeddingClient}: nenhum teste chama um
 * provedor externo.
 *
 * <p>A observabilidade fica ligada: por padrão, o Spring Boot desliga exportação de métricas e
 * tracing nos testes, e sem ela as métricas do Prompt 18 simplesmente não existiriam para serem
 * verificadas. A amostragem de traces continua em zero no perfil {@code dev}, então nada é exportado.
 *
 * <p>Requer Docker em execução.
 */
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
    PostgresTestcontainersConfiguration.class,
    MinioTestcontainersConfiguration.class,
    RabbitMqTestcontainersConfiguration.class,
    StubLlmClientConfiguration.class,
    StubEmbeddingClientConfiguration.class
})
public abstract class AbstractApiIT {}
