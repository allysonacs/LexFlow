package com.lexflow.domain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A divisão em trechos decide o que o modelo vê. Estes testes fixam as três garantias que a cadeia de
 * prompts depende: nenhum trecho passa do limite, a ordem é a do documento e a mesma entrada produz
 * sempre a mesma saída.
 */
class TextChunkerTest {

    private static final ChunkingPolicy POLICY = new ChunkingPolicy(300, 80);

    private final TextChunker chunker = new TextChunker(POLICY);

    @Test
    @DisplayName("um texto curto vira um único trecho, sem alterações")
    void shouldKeepShortTextInASingleChunk() {
        String text = "Art. 1º Esta política define as alçadas de assinatura de contratos.";

        List<TextChunk> chunks = chunker.split(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().index()).isZero();
        assertThat(chunks.getFirst().content()).isEqualTo(text);
    }

    @Test
    @DisplayName("parágrafos são mantidos inteiros enquanto couberem no limite")
    void shouldGroupParagraphsWhileTheyFit() {
        String text =
                """
                Art. 1º A contratação de fornecedores depende de cadastro válido.

                Art. 2º O cadastro é válido por doze meses.

                Art. 3º A renovação é automática quando não houver pendência fiscal.
                """;

        List<TextChunk> chunks = chunker.split(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).contains("Art. 1º", "Art. 2º", "Art. 3º");
        // Parágrafos diferentes continuam separados por linha em branco dentro do trecho.
        assertThat(chunks.getFirst().content()).contains("\n\n");
    }

    @Test
    @DisplayName("nenhum trecho passa do limite, e a ordem é a do documento")
    void shouldRespectTheMaximumSizeAndKeepOrder() {
        String paragraph = "A".repeat(250);
        String text = String.join("\n\n", paragraph, paragraph, paragraph, paragraph);

        List<TextChunk> chunks = chunker.split(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.content().length()).isLessThanOrEqualTo(POLICY.maxCharacters()));
        assertThat(chunks.stream().map(TextChunk::index)).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
    }

    @Test
    @DisplayName("trechos consecutivos se sobrepõem, para uma regra não se perder na fronteira")
    void shouldOverlapConsecutiveChunks() {
        String text = String.join(
                "\n\n",
                "Parágrafo um sobre prazos contratuais e suas condições gerais de vigência.",
                "Parágrafo dois sobre multas aplicáveis em caso de descumprimento do prazo.",
                "Parágrafo três sobre a rescisão antecipada mediante aviso prévio de trinta dias.",
                "Parágrafo quatro sobre a obrigação de manter a documentação fiscal regular.",
                "Parágrafo cinco sobre a competência do foro eleito pelas partes contratantes.");

        List<TextChunk> chunks = chunker.split(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        String firstChunk = chunks.getFirst().content();
        String secondChunk = chunks.get(1).content();
        String lastParagraphOfFirst = firstChunk.substring(firstChunk.lastIndexOf("\n\n") + 2);
        assertThat(secondChunk).startsWith(lastParagraphOfFirst);
    }

    @Test
    @DisplayName("um parágrafo maior que o limite é quebrado em frases")
    void shouldSplitLongParagraphIntoSentences() {
        String sentence = "Esta é uma frase de tamanho razoável sobre obrigações contratuais. ";
        String text = sentence.repeat(10);

        List<TextChunk> chunks = chunker.split(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.content().length()).isLessThanOrEqualTo(POLICY.maxCharacters()));
        assertThat(chunks.getFirst().content()).doesNotContain("\n\n");
    }

    @Test
    @DisplayName("uma frase única maior que o limite é cortada, em vez de estourar o trecho")
    void shouldHardSplitASentenceLongerThanTheLimit() {
        String text = "palavra ".repeat(200).strip();

        List<TextChunk> chunks = chunker.split(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.content().length()).isLessThanOrEqualTo(POLICY.maxCharacters()));
    }

    @Test
    @DisplayName("a mesma entrada produz sempre a mesma divisão")
    void shouldBeDeterministic() {
        String text = "Cláusula sobre pagamento. ".repeat(40);

        assertThat(chunker.split(text)).isEqualTo(chunker.split(text));
    }

    @Test
    @DisplayName("texto vazio ou em branco não gera trecho nenhum")
    void shouldReturnNoChunksForBlankText() {
        assertThat(chunker.split(null)).isEmpty();
        assertThat(chunker.split("   \n\n  ")).isEmpty();
    }

    @Test
    @DisplayName("sem sobreposição configurada, nenhum pedaço é repetido")
    void shouldSupportZeroOverlap() {
        TextChunker withoutOverlap = new TextChunker(new ChunkingPolicy(200, 0));
        String text = String.join("\n\n", "A".repeat(150), "B".repeat(150), "C".repeat(150));

        List<TextChunk> chunks = withoutOverlap.split(text);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).content()).doesNotContain("B");
        assertThat(chunks.get(1).content()).doesNotContain("A", "C");
    }

    @Test
    @DisplayName("uma sobreposição maior que o limite é recusada na configuração")
    void shouldRejectOverlapGreaterThanMaximum() {
        assertThatThrownBy(() -> new ChunkingPolicy(300, 300))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlapCharacters");
        assertThatThrownBy(() -> new ChunkingPolicy(10, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCharacters");
        assertThatThrownBy(() -> new ChunkingPolicy(300, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
