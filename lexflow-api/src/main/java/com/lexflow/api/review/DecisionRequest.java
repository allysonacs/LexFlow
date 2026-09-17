package com.lexflow.api.review;

/**
 * Corpo do registro de uma decisão.
 *
 * @param decisionType {@code APPROVED}, {@code REJECTED} ou {@code RETURNED_FOR_CORRECTION}
 * @param decidedBy responsável; opcional no corpo, porque pode vir do cabeçalho {@code X-User-Id}
 * @param comments obrigatório na devolução para correção, que precisa explicar o que falta
 */
public record DecisionRequest(String decisionType, String decidedBy, String comments) {}
