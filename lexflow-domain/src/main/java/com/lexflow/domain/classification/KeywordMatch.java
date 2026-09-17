package com.lexflow.domain.classification;

import com.lexflow.domain.legalcase.LegalCaseType;
import java.util.Objects;

/**
 * Uma palavra-chave encontrada nos sinais da demanda.
 *
 * @param keyword a palavra-chave já normalizada (minúsculas, sem acento), como aparece na tabela
 * @param caseType tipo de demanda que ela indica
 */
public record KeywordMatch(String keyword, LegalCaseType caseType) {

    public KeywordMatch {
        Objects.requireNonNull(caseType, "caseType não pode ser nulo");
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("keyword é obrigatória");
        }
    }
}
