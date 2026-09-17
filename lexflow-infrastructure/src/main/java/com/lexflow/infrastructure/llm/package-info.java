/**
 * Adapter do provedor de LLM (Prompt 10): cliente HTTP genérico e resiliente para a Messages API da
 * Anthropic.
 *
 * <p>Não conhece demanda jurídica, pergunta nem RAG. Só transporta o prompt, protege a chamada
 * (timeout, retry, circuit breaker, bulkhead) e valida o formato da resposta.
 */
package com.lexflow.infrastructure.llm;
