/**
 * Segunda checagem das respostas jurídicas críticas (self-verification, Prompt 14).
 *
 * <p>Uma camada extra contra a alucinação residual: para as perguntas mais críticas, uma segunda
 * chamada ao modelo confere se a resposta é mesmo sustentada pelos trechos que ela citou. A checagem
 * sinaliza; ela nunca reescreve a resposta original.
 */
package com.lexflow.application.verification;
