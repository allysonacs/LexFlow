package com.lexflow.domain.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Divide o texto de uma fonte normativa em trechos coerentes (Prompt 12, item 2).
 *
 * <p>É regra pura, e por isso mora no domínio: nenhuma chamada externa, resultado sempre igual para a
 * mesma entrada. Isso importa porque a divisão decide o que o modelo vê — reindexar a mesma fonte
 * duas vezes precisa produzir exatamente os mesmos trechos, ou uma citação antiga deixaria de bater
 * com o texto atual.
 *
 * <p><strong>Como a divisão é feita</strong>, do maior para o menor:
 *
 * <ol>
 *   <li>o texto é quebrado em parágrafos (linhas em branco), que é como uma norma se organiza;
 *   <li>um parágrafo maior que o limite é quebrado em frases;
 *   <li>uma frase ainda maior que o limite — uma tabela ou uma lista sem pontuação — é cortada no
 *       limite, porque o trecho precisa caber na coluna e na janela do modelo de embeddings.
 * </ol>
 *
 * <p>Os pedaços resultantes são agrupados de forma gulosa até o limite, e cada novo trecho repete os
 * últimos pedaços do anterior enquanto couberem na sobreposição configurada. A repetição nunca
 * abrange o trecho anterior inteiro: se abrangesse, a divisão deixaria de avançar.
 */
public final class TextChunker {

    /** Duas quebras de linha, com espaços entre elas, separam parágrafos. */
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n[ \\t]*\\n\\s*");

    /** Fim de frase: pontuação seguida de espaço. */
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.;:!?])\\s+");

    private final ChunkingPolicy policy;

    public TextChunker(ChunkingPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy não pode ser nulo");
    }

    public ChunkingPolicy policy() {
        return policy;
    }

    /**
     * Divide o texto.
     *
     * @return os trechos, na ordem do documento; vazia se o texto não tiver conteúdo
     */
    public List<TextChunk> split(String text) {
        List<Segment> segments = segmentsOf(text);
        if (segments.isEmpty()) {
            return List.of();
        }

        List<TextChunk> chunks = new ArrayList<>();
        List<Segment> current = new ArrayList<>();
        int currentLength = 0;

        for (Segment segment : segments) {
            if (!current.isEmpty() && lengthWith(current, currentLength, segment) > policy.maxCharacters()) {
                chunks.add(new TextChunk(chunks.size(), join(current)));
                current = overlapOf(current);
                currentLength = current.isEmpty() ? 0 : join(current).length();
                if (!current.isEmpty() && lengthWith(current, currentLength, segment) > policy.maxCharacters()) {
                    // A sobreposição não cabe junto com o pedaço seguinte: o trecho novo começa sem ela.
                    current = new ArrayList<>();
                    currentLength = 0;
                }
            }
            currentLength = current.isEmpty() ? segment.text().length() : lengthWith(current, currentLength, segment);
            current.add(segment);
        }
        chunks.add(new TextChunk(chunks.size(), join(current)));
        return List.copyOf(chunks);
    }

    /** Tamanho que o trecho em construção teria com mais um pedaço. */
    private static int lengthWith(List<Segment> current, int currentLength, Segment segment) {
        return currentLength + separator(current.getLast(), segment).length() + segment.text().length();
    }

    /** Quebra o texto em pedaços que cabem, isoladamente, no limite de um trecho. */
    private List<Segment> segmentsOf(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Segment> segments = new ArrayList<>();
        String[] paragraphs = PARAGRAPH_BREAK.split(text.replace("\r\n", "\n").replace('\r', '\n').strip());
        int paragraphIndex = 0;
        for (String rawParagraph : paragraphs) {
            String paragraph = rawParagraph.strip();
            if (paragraph.isEmpty()) {
                continue;
            }
            for (String piece : pieces(paragraph)) {
                segments.add(new Segment(piece, paragraphIndex));
            }
            paragraphIndex++;
        }
        return segments;
    }

    /** Um parágrafo vira um pedaço só; se não couber, vira frases; se ainda não couber, é cortado. */
    private List<String> pieces(String paragraph) {
        if (paragraph.length() <= policy.maxCharacters()) {
            return List.of(paragraph);
        }
        List<String> pieces = new ArrayList<>();
        for (String rawSentence : SENTENCE_BREAK.split(paragraph)) {
            String sentence = rawSentence.strip();
            if (sentence.isEmpty()) {
                continue;
            }
            if (sentence.length() <= policy.maxCharacters()) {
                pieces.add(sentence);
            } else {
                for (int start = 0; start < sentence.length(); start += policy.maxCharacters()) {
                    pieces.add(sentence.substring(start, Math.min(sentence.length(), start + policy.maxCharacters()))
                            .strip());
                }
            }
        }
        pieces.removeIf(String::isEmpty);
        return pieces;
    }

    /**
     * Últimos pedaços do trecho recém-fechado que cabem na sobreposição.
     *
     * <p>Nunca devolve o trecho inteiro: sobra sempre ao menos um pedaço fora, para que o trecho
     * seguinte seja de fato diferente do anterior.
     */
    private List<Segment> overlapOf(List<Segment> closed) {
        List<Segment> overlap = new ArrayList<>();
        if (policy.overlapCharacters() == 0) {
            return overlap;
        }
        int length = 0;
        // Começa em 1, e não em 0: o primeiro pedaço do trecho fechado nunca é repetido.
        for (int i = closed.size() - 1; i > 0; i--) {
            Segment segment = closed.get(i);
            int candidateLength = overlap.isEmpty()
                    ? segment.text().length()
                    : segment.text().length() + separator(segment, overlap.getFirst()).length() + length;
            if (candidateLength > policy.overlapCharacters()) {
                break;
            }
            overlap.addFirst(segment);
            length = candidateLength;
        }
        return overlap;
    }

    private static String join(List<Segment> segments) {
        StringBuilder text = new StringBuilder(segments.getFirst().text());
        for (int i = 1; i < segments.size(); i++) {
            text.append(separator(segments.get(i - 1), segments.get(i))).append(segments.get(i).text());
        }
        return text.toString();
    }

    /** Pedaços do mesmo parágrafo são reunidos por espaço; de parágrafos diferentes, por linha em branco. */
    private static String separator(Segment previous, Segment next) {
        return previous.paragraphIndex() == next.paragraphIndex() ? " " : "\n\n";
    }

    /** Pedaço indivisível de texto e o parágrafo de onde ele veio. */
    private record Segment(String text, int paragraphIndex) {}
}
