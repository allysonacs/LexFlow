package com.lexflow.infrastructure.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Configuração base do Testcontainers para testes de integração que precisam de PostgreSQL.
 *
 * <p>Usa a imagem do {@code pgvector}, pois a base de conhecimento exige a extensão {@code vector}
 * (seção 6). Com {@link ServiceConnection}, o Spring Boot configura automaticamente o datasource
 * apontando para o container, sem necessidade de propriedades manuais.
 *
 * <p>Uso em um teste de integração:
 *
 * <pre>{@code
 * @SpringBootTest
 * @Import(PostgresTestcontainersConfiguration.class)
 * class MeuTesteDeIntegracao { ... }
 * }</pre>
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfiguration {

    /** Imagem do PostgreSQL com a extensão pgvector pré-instalada. */
    public static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE);
    }
}
