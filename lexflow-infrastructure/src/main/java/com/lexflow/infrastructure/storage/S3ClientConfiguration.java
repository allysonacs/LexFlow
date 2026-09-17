package com.lexflow.infrastructure.storage;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Monta o cliente S3 a partir de {@link DocumentStorageProperties}.
 *
 * <p>O mesmo bean serve aos dois ambientes: sem {@code endpoint} ele fala com o S3 da AWS; com
 * {@code endpoint} e {@code path-style-access}, com o MinIO local. Construir o cliente não abre
 * conexão nem resolve credenciais, de modo que a aplicação sobe mesmo com o storage fora do ar — a
 * falha aparece na primeira operação, como erro daquela requisição, e não como um serviço que não
 * inicia.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DocumentStorageProperties.class)
public class S3ClientConfiguration {

    /**
     * Tempos limite explícitos, como exige a seção 11: nenhuma integração externa pode ficar
     * pendurada indefinidamente segurando uma thread.
     */
    private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public S3Client s3Client(DocumentStorageProperties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                        // Quem repete é o Resilience4j, no decorador da porta de storage (Prompt 17).
                        // Somar as tentativas do SDK às dele multiplicaria as chamadas e tornaria o
                        // tempo total de uma falha imprevisível.
                        .retryStrategy(AwsRetryStrategy.doNotRetry())
                        .build());

        if (properties.hasCustomEndpoint()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        if (properties.pathStyleAccess()) {
            // O MinIO endereça o bucket no caminho da URL, e não como subdomínio.
            builder.forcePathStyle(true);
        }
        builder.credentialsProvider(
                properties.hasStaticCredentials()
                        ? StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()))
                        // builder().build(), e não create(): o create() devolve um singleton
                        // compartilhado, que é fechado junto com o primeiro cliente que o usar.
                        : DefaultCredentialsProvider.builder().build());

        return builder.build();
    }
}
