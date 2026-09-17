package com.lexflow.api.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Troca o cliente LLM real pelo {@link StubLlmClient} em todos os testes da API.
 *
 * <p>O cliente real continua no contexto — o teste de contexto confere a configuração dele —, mas
 * quem pede um {@code LlmClientPort} recebe o dublê.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubLlmClientConfiguration {

    @Bean
    @Primary
    public StubLlmClient stubLlmClient(ObjectMapper objectMapper) {
        return new StubLlmClient(objectMapper);
    }
}
