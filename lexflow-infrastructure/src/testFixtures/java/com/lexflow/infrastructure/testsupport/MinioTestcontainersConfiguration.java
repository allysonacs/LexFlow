package com.lexflow.infrastructure.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Configuração base do Testcontainers para testes que precisam do storage de documentos.
 *
 * <p>Sobe um MinIO — o mesmo serviço usado em desenvolvimento local — e aponta as propriedades
 * {@code lexflow.storage.*} para ele. Testar contra um MinIO real, e não contra um dublê do S3, é o
 * que dá alguma garantia sobre o adapter: erros de {@code path-style access}, de credencial e de
 * bucket inexistente só aparecem contra um serviço de verdade.
 *
 * <p>O bucket é criado pela própria aplicação, com {@code create-bucket-if-missing}, para que o teste
 * exercite também esse caminho.
 *
 * <p>Uso em um teste de integração:
 *
 * <pre>{@code
 * @SpringBootTest
 * @Import({PostgresTestcontainersConfiguration.class, MinioTestcontainersConfiguration.class})
 * class MeuTesteDeIntegracao { ... }
 * }</pre>
 */
@TestConfiguration(proxyBeanMethods = false)
public class MinioTestcontainersConfiguration {

    /**
     * Mesma imagem usada no {@code compose.yaml}, para que teste e desenvolvimento não divirjam.
     *
     * <p>Vem do {@code quay.io}, e não do Docker Hub: o repositório {@code minio/minio} do Hub deixou
     * de ser público, e o padrão do Testcontainers ainda aponta para lá. O
     * {@code asCompatibleSubstituteFor} é o que autoriza a troca de registry sem que o Testcontainers
     * recuse a imagem.
     */
    public static final DockerImageName MINIO_IMAGE = DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
            .asCompatibleSubstituteFor("minio/minio");

    public static final String TEST_USER = "lexflow";
    public static final String TEST_PASSWORD = "lexflow123";
    public static final String TEST_BUCKET = "lexflow-documents-test";

    /** Container do MinIO; o Spring Boot cuida de iniciá-lo e encerrá-lo. */
    @Bean
    public MinIOContainer minioContainer() {
        return new MinIOContainer(MINIO_IMAGE).withUserName(TEST_USER).withPassword(TEST_PASSWORD);
    }

    /**
     * Aponta a configuração do storage para o container.
     *
     * <p>Usa {@link DynamicPropertyRegistrar}, e não {@code @DynamicPropertySource}, porque só assim
     * as propriedades podem depender de um container declarado como bean.
     */
    @Bean
    public DynamicPropertyRegistrar minioProperties(MinIOContainer container) {
        return registry -> {
            registry.add("lexflow.storage.bucket", () -> TEST_BUCKET);
            registry.add("lexflow.storage.endpoint", container::getS3URL);
            registry.add("lexflow.storage.access-key", container::getUserName);
            registry.add("lexflow.storage.secret-key", container::getPassword);
            registry.add("lexflow.storage.path-style-access", () -> true);
            registry.add("lexflow.storage.create-bucket-if-missing", () -> true);
        };
    }
}
