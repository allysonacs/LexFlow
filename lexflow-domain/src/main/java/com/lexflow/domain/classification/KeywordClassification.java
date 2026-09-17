package com.lexflow.domain.classification;

import com.lexflow.domain.legalcase.LegalCaseType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resultado bruto da busca por palavras-chave: o que foi encontrado e quanto cada tipo pontuou.
 *
 * <p>Não decide nada sozinho. Quem combina este resultado com o tipo informado pelo requisitante é
 * {@link LegalCaseClassification}.
 */
public record KeywordClassification(List<KeywordMatch> matches) {

    public KeywordClassification {
        matches = List.copyOf(Objects.requireNonNull(matches, "matches não pode ser nulo"));
    }

    /** Quantidade de ocorrências por tipo; tipos sem ocorrência ficam de fora. */
    public Map<LegalCaseType, Integer> scores() {
        Map<LegalCaseType, Integer> scores = new EnumMap<>(LegalCaseType.class);
        matches.forEach(match -> scores.merge(match.caseType(), 1, Integer::sum));
        return Collections.unmodifiableMap(scores);
    }

    /**
     * Tipos com a maior pontuação. Vazio quando nada foi encontrado; mais de um quando há empate.
     */
    public Set<LegalCaseType> topTypes() {
        Map<LegalCaseType, Integer> scores = scores();
        int best = scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        Set<LegalCaseType> top = EnumSet.noneOf(LegalCaseType.class);
        scores.forEach((type, score) -> {
            if (score == best) {
                top.add(type);
            }
        });
        return Collections.unmodifiableSet(top);
    }

    /** O tipo mais apontado, apenas quando não há empate. */
    public Optional<LegalCaseType> suggestedType() {
        Set<LegalCaseType> top = topTypes();
        return top.size() == 1 ? Optional.of(top.iterator().next()) : Optional.empty();
    }

    /** Indica se ao menos uma palavra-chave foi encontrada. */
    public boolean hasEvidence() {
        return !matches.isEmpty();
    }

    /** Palavras-chave encontradas para um tipo, sem repetição e na ordem em que apareceram. */
    public List<String> keywordsFor(LegalCaseType caseType) {
        return matches.stream()
                .filter(match -> match.caseType() == caseType)
                .map(KeywordMatch::keyword)
                .distinct()
                .toList();
    }
}
