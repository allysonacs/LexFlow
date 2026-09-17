package com.lexflow.api.support;

import com.lexflow.infrastructure.testsupport.LexicalEmbeddingClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Troca o provedor de embeddings pelo modelo determinístico em todos os testes da API (Prompt 12).
 *
 * <p>Mesmo princípio do {@link StubLlmClientConfiguration}: nenhum teste automatizado chama um
 * provedor externo, e o resultado da recuperação não depende de rede nem de chave de API.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubEmbeddingClientConfiguration {

    @Bean
    @Primary
    public LexicalEmbeddingClient lexicalEmbeddingClient() {
        return new LexicalEmbeddingClient();
    }
}
