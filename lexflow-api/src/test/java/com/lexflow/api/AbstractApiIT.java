package com.lexflow.api;

import com.lexflow.infrastructure.testsupport.MinioTestcontainersConfiguration;
import com.lexflow.infrastructure.testsupport.PostgresTestcontainersConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base dos testes de integração da API: a aplicação inteira no ar, com PostgreSQL e MinIO reais.
 *
 * <p>Todas as classes compartilham esta mesma configuração de propósito — assim o Spring reaproveita
 * o contexto e os containers entre elas, em vez de subir um par novo por classe de teste.
 *
 * <p>Requer Docker em execução.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainersConfiguration.class, MinioTestcontainersConfiguration.class})
public abstract class AbstractApiIT {}
