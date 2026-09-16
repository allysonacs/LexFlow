package com.lexflow.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração do storage de documentos, lida de {@code lexflow.storage}.
 *
 * <p>O mesmo conjunto de propriedades serve ao S3 e ao MinIO: o que muda é o {@code endpoint} e o
 * {@code pathStyleAccess}. Em produção, deixar {@code accessKey} e {@code secretKey} em branco é o
 * caminho recomendado — sem chaves explícitas, o SDK usa a cadeia padrão de credenciais (variáveis de
 * ambiente, perfil ou role da instância), e nenhum segredo precisa existir em arquivo de
 * configuração.
 *
 * @param bucket bucket onde os documentos são gravados
 * @param region região informada ao SDK; o MinIO ignora, mas o SDK exige uma
 * @param endpoint endereço alternativo do serviço; vazio significa o S3 da AWS
 * @param pathStyleAccess {@code true} para endereçar o bucket no caminho da URL, como o MinIO exige
 * @param createBucketIfMissing cria o bucket na primeira gravação, se ele não existir; conveniência
 *     de desenvolvimento e teste, que deve permanecer desligada em produção
 */
@ConfigurationProperties(prefix = "lexflow.storage")
public record DocumentStorageProperties(
        String bucket,
        String region,
        String endpoint,
        String accessKey,
        String secretKey,
        boolean pathStyleAccess,
        boolean createBucketIfMissing) {

    public DocumentStorageProperties {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("lexflow.storage.bucket é obrigatório");
        }
        region = region == null || region.isBlank() ? "us-east-1" : region;
        endpoint = endpoint == null || endpoint.isBlank() ? null : endpoint.trim();
    }

    /** Indica se o SDK deve apontar para um endpoint alternativo, como o MinIO local. */
    public boolean hasCustomEndpoint() {
        return endpoint != null;
    }

    /**
     * Indica se há credenciais explícitas na configuração. Quando não há, o SDK resolve as
     * credenciais pela cadeia padrão.
     */
    public boolean hasStaticCredentials() {
        return accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank();
    }
}
