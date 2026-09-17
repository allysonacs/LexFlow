/**
 * Adapter do provedor de embeddings da base normativa (Prompt 12).
 *
 * <p>É uma integração separada do cliente LLM: o provedor de geração de texto do LexFlow (Anthropic)
 * não expõe endpoint de embeddings, e o modelo de vetores tem ciclo de vida próprio — trocá-lo obriga
 * a reindexar toda a base normativa.
 */
package com.lexflow.infrastructure.embedding;
