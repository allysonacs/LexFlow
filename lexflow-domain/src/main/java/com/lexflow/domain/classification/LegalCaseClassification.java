package com.lexflow.domain.classification;

import com.lexflow.domain.exception.LegalCaseTypeNotClassifiableException;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tipo final de uma demanda e como ele foi decidido.
 *
 * <p>A regra é deliberadamente conservadora:
 *
 * <ul>
 *   <li>tipo informado pelo requisitante: é o que vale, sempre. As palavras-chave só confirmam
 *       ({@link ClassificationOutcome#CONFIRMED}), ficam em silêncio
 *       ({@link ClassificationOutcome#UNCONFIRMED}) ou apontam divergência
 *       ({@link ClassificationOutcome#DIVERGENT});
 *   <li>tipo não informado: as palavras-chave decidem, desde que apontem um único tipo
 *       ({@link ClassificationOutcome#INFERRED}). Sem evidência ou com empate, a demanda não é
 *       classificada — chutar um tipo geraria um checklist errado mais adiante.
 * </ul>
 *
 * @param declaredType tipo informado na ingestão; nulo quando o requisitante não informou
 * @param resolvedType tipo que o restante do pipeline deve usar
 */
public record LegalCaseClassification(
        LegalCaseType declaredType,
        LegalCaseType resolvedType,
        ClassificationOutcome outcome,
        KeywordClassification keywords) {

    public LegalCaseClassification {
        Objects.requireNonNull(resolvedType, "resolvedType não pode ser nulo");
        Objects.requireNonNull(outcome, "outcome não pode ser nulo");
        Objects.requireNonNull(keywords, "keywords não pode ser nulo");
        if (declaredType != null && declaredType != resolvedType) {
            throw new IllegalArgumentException("o tipo informado pelo requisitante nunca é substituído");
        }
        if ((declaredType == null) != (outcome == ClassificationOutcome.INFERRED)) {
            throw new IllegalArgumentException("INFERRED vale exatamente para quando o tipo não foi informado");
        }
    }

    /**
     * Combina o tipo informado com o resultado das palavras-chave.
     *
     * @param declaredType pode ser nulo
     * @throws LegalCaseTypeNotClassifiableException se o tipo não foi informado e as palavras-chave
     *     não apontam um único tipo
     */
    public static LegalCaseClassification resolve(LegalCaseType declaredType, KeywordClassification keywords) {
        Objects.requireNonNull(keywords, "keywords não pode ser nulo");
        Set<LegalCaseType> top = keywords.topTypes();

        if (declaredType == null) {
            LegalCaseType inferred = keywords.suggestedType()
                    .orElseThrow(() -> new LegalCaseTypeNotClassifiableException(top));
            return new LegalCaseClassification(null, inferred, ClassificationOutcome.INFERRED, keywords);
        }

        ClassificationOutcome outcome;
        if (top.isEmpty()) {
            outcome = ClassificationOutcome.UNCONFIRMED;
        } else if (top.contains(declaredType)) {
            outcome = ClassificationOutcome.CONFIRMED;
        } else {
            outcome = ClassificationOutcome.DIVERGENT;
        }
        return new LegalCaseClassification(declaredType, declaredType, outcome, keywords);
    }

    /** Tipo sugerido pelas palavras-chave, quando elas apontam um único tipo. */
    public Optional<LegalCaseType> keywordSuggestion() {
        return keywords.suggestedType();
    }

    /**
     * Resumo legível do resultado, próprio para o motivo gravado no histórico de status.
     *
     * <p>Traz apenas o tipo e as palavras-chave da tabela — nunca trechos do texto do requisitante
     * nem do conteúdo dos documentos (seção 12).
     */
    public String summary() {
        String evidence = describeEvidence();
        return switch (outcome) {
            case CONFIRMED -> "Tipo %s informado e confirmado por palavras-chave (%s)".formatted(resolvedType, evidence);
            case UNCONFIRMED -> "Tipo %s informado; nenhuma palavra-chave para confirmá-lo".formatted(resolvedType);
            case DIVERGENT -> "Tipo %s informado, mas as palavras-chave apontam outro tipo (%s); revisar na análise humana"
                    .formatted(resolvedType, evidence);
            case INFERRED -> "Tipo %s deduzido por palavras-chave (%s)".formatted(resolvedType, evidence);
        };
    }

    private String describeEvidence() {
        Map<LegalCaseType, Integer> scores = keywords.scores();
        return scores.keySet().stream()
                .map(type -> "%s=%d %s".formatted(type, scores.get(type), keywords.keywordsFor(type)))
                .collect(Collectors.joining("; "));
    }
}
