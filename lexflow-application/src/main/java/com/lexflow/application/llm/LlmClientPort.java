package com.lexflow.application.llm;

/**
 * Porta de saída para o provedor de LLM.
 *
 * <p>A implementação cuida de timeout, retry, circuit breaker e da validação do formato da resposta.
 * Quem chama recebe uma resposta pronta para uso ou uma das exceções abaixo, que dizem o que fazer
 * em seguida.
 */
public interface LlmClientPort {

    /**
     * Envia o prompt e devolve a resposta do modelo.
     *
     * @return a resposta; quando {@link LlmRequest#outputSchema()} foi informado,
     *     {@link LlmResponse#structuredOutput()} já foi validado contra ele
     * @throws LlmUnavailableException provedor fora do ar, sobrecarregado, lento demais ou com o
     *     circuito aberto — vale tentar de novo mais tarde
     * @throws LlmRequestRejectedException o provedor recusou a requisição (credencial, modelo ou
     *     parâmetro inválido) — tentar de novo não resolve
     * @throws LlmResponseValidationException a resposta não segue o schema pedido, não é JSON ou veio
     *     truncada — nunca deve seguir adiante no pipeline
     * @throws LlmRefusalException o modelo se recusou a responder
     */
    LlmResponse complete(LlmRequest request);
}
