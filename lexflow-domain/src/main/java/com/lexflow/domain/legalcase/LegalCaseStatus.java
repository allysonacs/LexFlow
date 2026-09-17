package com.lexflow.domain.legalcase;

import com.lexflow.domain.exception.UnknownLegalCaseStatusException;

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

    /**
     * Converte o texto recebido de um cliente externo no status correspondente.
     *
     * <p>Fica no domínio, e não no controller, pelo mesmo motivo de {@link LegalCaseType#of}: um
     * valor desconhecido é recusado da mesma forma em qualquer ponto de entrada.
     *
     * @throws UnknownLegalCaseStatusException se o valor não corresponder a nenhum status
     */
    public static LegalCaseStatus of(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        return java.util.Arrays.stream(values())
                .filter(status -> status.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new UnknownLegalCaseStatusException(value, supportedValues()));
    }

    /** Nomes aceitos, na ordem declarada, para compor mensagens de erro. */
    public static java.util.Set<String> supportedValues() {
        return java.util.Arrays.stream(values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
