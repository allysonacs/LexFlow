package com.lexflow.domain.legalcase;

/**
 * Situação de uma demanda jurídica ao longo do pipeline (seção 4 da base de conhecimento).
 *
 * <p>As transições permitidas entre estes valores não ficam aqui: elas são responsabilidade de
 * {@link LegalCaseStatusTransition}, para poderem ser testadas e evoluídas isoladamente.
 */
public enum LegalCaseStatus {

    /** Demanda recebida, ainda não classificada. */
    RECEIVED,

    /** Classificação do tipo de demanda em andamento. */
    CLASSIFYING,

    /** Extração de texto e de fatos dos documentos em andamento. */
    EXTRACTING,

    /** Análise da IA (prompt chain) em andamento. */
    AI_ANALYSIS_IN_PROGRESS,

    /** Aguardando a decisão do responsável humano. */
    PENDING_HUMAN_REVIEW,

    /** Demanda aprovada pelo responsável humano. */
    APPROVED,

    /** Demanda reprovada pelo responsável humano. */
    REJECTED,

    /** Devolvida para correção: volta para {@link #RECEIVED} quando a documentação for reenviada. */
    RETURNED_FOR_CORRECTION,

    /** Estado terminal, atingido depois que uma aprovação ou reprovação é processada. */
    CLOSED;

    /** Indica se este é um estado terminal, do qual não sai nenhuma transição. */
    public boolean isTerminal() {
        return this == CLOSED;
    }
}
