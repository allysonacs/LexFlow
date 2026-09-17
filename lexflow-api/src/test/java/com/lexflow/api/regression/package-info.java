/**
 * Dataset de regressão de prompts (Prompt 19).
 *
 * <p>Roda o pipeline de IA contra casos de teste dourados usando o provedor real, para responder uma
 * pergunta que nenhum teste com dublê responde: <em>mudar este prompt, ou trocar de modelo, piorou as
 * respostas?</em>
 *
 * <p>Fica fora de qualquer execução automática: as chamadas custam dinheiro e não são
 * determinísticas. Roda com {@code ./gradlew regressionTest}.
 */
package com.lexflow.api.regression;
