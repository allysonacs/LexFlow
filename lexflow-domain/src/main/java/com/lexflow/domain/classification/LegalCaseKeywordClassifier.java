package com.lexflow.domain.classification;

import com.lexflow.domain.legalcase.LegalCaseType;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Classificador do tipo de demanda por palavras-chave, sem nenhuma chamada a LLM.
 *
 * <p>Os sinais são textos curtos que o requisitante escolheu: nomes de arquivo e a descrição da
 * demanda. Cada sinal é normalizado — minúsculas, sem acento, com {@code _}, {@code -} e {@code .}
 * tratados como espaço — e percorrido palavra a palavra. Em cada posição vale a <strong>expressão
 * mais longa</strong> da tabela: em "acordo de confidencialidade" conta a expressão inteira, que
 * indica contrato, e não a palavra "acordo", que indicaria pagamento de acordo. Cada ocorrência soma
 * um ponto para o tipo correspondente.
 *
 * <p>A tabela é um ponto de configuração de negócio. Está no código porque, por ora, muda junto com
 * o glossário da seção 5; se a área jurídica passar a ajustá-la com frequência, ela deve migrar para
 * o banco, como as regras de checklist.
 */
public class LegalCaseKeywordClassifier {

    /** Tabela padrão. As expressões já estão normalizadas: minúsculas e sem acento. */
    private static final Map<LegalCaseType, List<String>> DEFAULT_KEYWORDS = defaultKeywords();

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** Expressão (em palavras) → tipo. */
    private final Map<List<String>, LegalCaseType> dictionary;

    private final int longestExpression;

    /** Classificador com a tabela padrão. */
    public LegalCaseKeywordClassifier() {
        this(DEFAULT_KEYWORDS);
    }

    /**
     * Classificador com uma tabela própria.
     *
     * @throws IllegalArgumentException se uma mesma expressão apontar para mais de um tipo — a
     *     ambiguidade tem de ser resolvida na tabela, e não na sorte da ordem de leitura
     */
    public LegalCaseKeywordClassifier(Map<LegalCaseType, List<String>> keywords) {
        Objects.requireNonNull(keywords, "keywords não pode ser nulo");
        Map<List<String>, LegalCaseType> built = new HashMap<>();
        keywords.forEach((type, expressions) -> expressions.forEach(expression -> {
            List<String> tokens = tokenize(expression);
            if (tokens.isEmpty()) {
                throw new IllegalArgumentException("expressão vazia na tabela de " + type);
            }
            LegalCaseType previous = built.putIfAbsent(tokens, type);
            if (previous != null && previous != type) {
                throw new IllegalArgumentException(
                        "a expressão '%s' aponta para %s e %s".formatted(String.join(" ", tokens), previous, type));
            }
        }));
        this.dictionary = Collections.unmodifiableMap(built);
        this.longestExpression = built.keySet().stream().mapToInt(List::size).max().orElse(0);
    }

    /**
     * Procura as palavras-chave nos sinais informados.
     *
     * @param signals textos a examinar; nulos e vazios são ignorados
     */
    public KeywordClassification classify(Collection<String> signals) {
        Objects.requireNonNull(signals, "signals não pode ser nulo");
        List<KeywordMatch> matches = new ArrayList<>();
        for (String signal : signals) {
            if (signal != null && !signal.isBlank()) {
                collectMatches(tokenize(signal), matches);
            }
        }
        return new KeywordClassification(matches);
    }

    /**
     * Combina a busca por palavras-chave com o tipo informado pelo requisitante.
     *
     * @param declaredType pode ser nulo
     * @see LegalCaseClassification#resolve
     */
    public LegalCaseClassification classify(LegalCaseType declaredType, Collection<String> signals) {
        return LegalCaseClassification.resolve(declaredType, classify(signals));
    }

    /** Tabela padrão, somente leitura, para consulta e documentação. */
    public static Map<LegalCaseType, List<String>> defaultKeywordTable() {
        return DEFAULT_KEYWORDS;
    }

    private void collectMatches(List<String> tokens, List<KeywordMatch> matches) {
        int position = 0;
        while (position < tokens.size()) {
            int consumed = 0;
            int maxLength = Math.min(longestExpression, tokens.size() - position);
            // Da expressão mais longa para a mais curta: a primeira que casar é a que vale.
            for (int length = maxLength; length > 0; length--) {
                List<String> candidate = tokens.subList(position, position + length);
                LegalCaseType type = dictionary.get(candidate);
                if (type != null) {
                    matches.add(new KeywordMatch(String.join(" ", candidate), type));
                    consumed = length;
                    break;
                }
            }
            position += Math.max(consumed, 1);
        }
    }

    /** Minúsculas, sem acento e quebrado em palavras. */
    static List<String> tokenize(String text) {
        String withoutAccents = DIACRITICS
                .matcher(Normalizer.normalize(text, Normalizer.Form.NFD))
                .replaceAll("");
        return Arrays.stream(SEPARATORS.split(withoutAccents.toLowerCase(Locale.ROOT)))
                .filter(token -> !token.isEmpty())
                .toList();
    }

    private static Map<LegalCaseType, List<String>> defaultKeywords() {
        Map<LegalCaseType, List<String>> keywords = new EnumMap<>(LegalCaseType.class);
        keywords.put(LegalCaseType.SUPPLIER_HIRING, List.of(
                "fornecedor", "fornecedores", "cnpj", "cartao cnpj", "contrato social",
                "habilitacao", "homologacao de fornecedor", "certidao negativa", "certidoes negativas",
                "due diligence", "prestador de servicos", "cadastro de fornecedor"));
        keywords.put(LegalCaseType.CONTRACT_SIGNING, List.of(
                "contrato", "contratos", "minuta", "minuta de contrato", "aditivo", "termo aditivo",
                "assinatura", "instrumento particular", "acordo de confidencialidade", "nda",
                "clausulas contratuais"));
        keywords.put(LegalCaseType.SETTLEMENT_PAYMENT, List.of(
                "acordo", "acordos", "termo de acordo", "acordo judicial", "acordo extrajudicial",
                "homologacao de acordo", "pagamento", "comprovante de pagamento", "indenizacao",
                "quitacao", "transacao", "deposito judicial"));
        keywords.put(LegalCaseType.LAWSUIT_CLOSURE, List.of(
                "processo", "processo judicial", "acao judicial", "encerramento", "arquivamento",
                "extincao", "sentenca", "transito em julgado", "peticao", "baixa processual",
                "desistencia"));
        keywords.put(LegalCaseType.PROPOSAL_ACCEPTANCE, List.of(
                "proposta", "propostas", "proposta comercial", "carta proposta", "oferta",
                "cotacao", "orcamento"));
        keywords.replaceAll((type, expressions) -> List.copyOf(expressions));
        return Collections.unmodifiableMap(keywords);
    }

    /** Tipos presentes na tabela em uso. */
    Set<LegalCaseType> coveredTypes() {
        return Set.copyOf(dictionary.values());
    }
}
