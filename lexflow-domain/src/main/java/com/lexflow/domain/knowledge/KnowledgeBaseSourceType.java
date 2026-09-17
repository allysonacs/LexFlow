package com.lexflow.domain.knowledge;

import com.lexflow.domain.exception.UnknownKnowledgeBaseSourceTypeException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Natureza de uma fonte normativa indexada para RAG (seção 6 da base de conhecimento).
 *
 * <p>O tipo não muda a recuperação — a busca é por similaridade —, mas acompanha o trecho citado até
 * o revisor humano: saber se o fundamento veio de uma lei ou de uma política interna muda o peso que
 * a pessoa dá a ele.
 */
public enum KnowledgeBaseSourceType {

    /** Lei, medida provisória ou código. */
    LAW,

    /** Norma infralegal: decreto, instrução normativa, resolução de agência reguladora. */
    REGULATION,

    /** Entendimento consolidado de tribunal: súmula, precedente, tese de repetitivo. */
    CASE_LAW,

    /** Política, norma ou alçada interna da empresa. */
    INTERNAL_POLICY,

    /** Modelo contratual padrão adotado pela empresa. */
    CONTRACT_TEMPLATE;

    /**
     * Converte o texto recebido de um cliente externo no tipo correspondente.
     *
     * @throws UnknownKnowledgeBaseSourceTypeException se o valor não corresponder a nenhum tipo
     */
    public static KnowledgeBaseSourceType of(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new UnknownKnowledgeBaseSourceTypeException(value, supportedValues()));
    }

    /** Nomes aceitos, na ordem declarada, para compor mensagens de erro. */
    public static Set<String> supportedValues() {
        return Arrays.stream(values())
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
