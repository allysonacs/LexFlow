/**
 * Porta genérica para chamar um LLM (Prompt 10).
 *
 * <p>Nada aqui conhece demanda jurídica, pergunta ou RAG: é o contrato de "mande este prompt, receba
 * esta resposta validada". As regras de negócio que usam o LLM (Prompts 11 em diante) montam o
 * prompt e interpretam a resposta; esta porta só transporta.
 */
package com.lexflow.application.llm;
