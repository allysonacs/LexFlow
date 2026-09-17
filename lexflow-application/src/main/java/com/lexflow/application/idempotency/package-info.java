/**
 * Idempotência dos endpoints de escrita (seção 11 da base de conhecimento).
 *
 * <p>A garantia vem sempre de uma restrição de unicidade no banco, e não de uma consulta feita antes
 * da gravação: só assim duas réplicas da API que recebam a mesma chave ao mesmo tempo deixam de
 * executar a operação duas vezes.
 */
package com.lexflow.application.idempotency;
