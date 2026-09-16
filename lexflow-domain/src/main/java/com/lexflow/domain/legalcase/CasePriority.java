package com.lexflow.domain.legalcase;

import com.lexflow.domain.exception.UnknownCasePriorityException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

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
    URGENT;

    /** Prioridade assumida quando o cliente não informa nenhuma. */
    public static final CasePriority DEFAULT = NORMAL;

    /**
     * Converte o texto recebido de um cliente externo na prioridade correspondente.
     *
     * @throws UnknownCasePriorityException se o valor não corresponder a nenhuma prioridade
     */
    public static CasePriority of(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(priority -> priority.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new UnknownCasePriorityException(value, supportedValues()));
    }

    /** Nomes aceitos, na ordem declarada, para compor mensagens de erro. */
    public static Set<String> supportedValues() {
        return Arrays.stream(values())
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
