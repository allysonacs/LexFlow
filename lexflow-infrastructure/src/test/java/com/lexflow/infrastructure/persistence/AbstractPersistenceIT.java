package com.lexflow.infrastructure.persistence;

import com.lexflow.infrastructure.testsupport.PostgresTestcontainersConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Base dos testes de integração de persistência.
 *
 * <p>Sobe um PostgreSQL com pgvector via Testcontainers, roda as migrations do Flyway e valida o
 * mapeamento das entidades contra o schema. Como todas as classes de teste compartilham esta mesma
 * configuração, o Spring reaproveita o contexto e o container entre elas.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfiguration.class)
public abstract class AbstractPersistenceIT {}
