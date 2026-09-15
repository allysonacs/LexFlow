package com.lexflow.domain.legalcase;

/**
 * Prioridade de tratamento de uma demanda, correspondente à coluna {@code legal_cases.priority}.
 *
 * <p>A base de conhecimento prevê a coluna, mas não fixa os valores possíveis. Esta escala é uma
 * proposta inicial e deve ser confirmada com a área de negócio antes de virar contrato com o
 * cliente da API.
 */
public enum CasePriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}
