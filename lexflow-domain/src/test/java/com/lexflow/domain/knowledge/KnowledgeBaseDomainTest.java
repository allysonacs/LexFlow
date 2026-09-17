package com.lexflow.domain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lexflow.domain.exception.UnknownKnowledgeBaseSourceTypeException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KnowledgeBaseDomainTest {

    @Test
    @DisplayName("uma fonte sem título é recusada: trecho citado sem fonte não serve ao revisor")
    void shouldRequireSourceTitle() {
        assertThatThrownBy(() -> new KnowledgeBaseSource(
                        UUID.randomUUID(), "  ", KnowledgeBaseSourceType.LAW, LocalDate.of(2021, 4, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
        assertThatThrownBy(() -> new KnowledgeBaseSource(
                        UUID.randomUUID(),
                        "t".repeat(KnowledgeBaseSource.MAX_TITLE_LENGTH + 1),
                        KnowledgeBaseSourceType.LAW,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("o título é normalizado e a data de vigência é opcional")
    void shouldNormalizeTitleAndAllowNullEffectiveDate() {
        KnowledgeBaseSource source = new KnowledgeBaseSource(
                UUID.randomUUID(), "  Lei 14.133/2021  ", KnowledgeBaseSourceType.LAW, null);

        assertThat(source.title()).isEqualTo("Lei 14.133/2021");
        assertThat(source.effectiveDate()).isNull();
    }

    @Test
    @DisplayName("o tipo de fonte é resolvido a partir do texto recebido, ignorando caixa e espaços")
    void shouldResolveSourceTypeFromText() {
        assertThat(KnowledgeBaseSourceType.of(" internal_policy "))
                .isEqualTo(KnowledgeBaseSourceType.INTERNAL_POLICY);
        assertThatThrownBy(() -> KnowledgeBaseSourceType.of("PORTARIA"))
                .isInstanceOf(UnknownKnowledgeBaseSourceTypeException.class)
                .hasMessageContaining("LAW");
        assertThat(KnowledgeBaseSourceType.supportedValues()).contains("LAW", "CASE_LAW", "CONTRACT_TEMPLATE");
    }

    @Test
    @DisplayName("um trecho nasce sem embedding e passa a indexado quando o vetor chega")
    void shouldTrackIndexingState() {
        KnowledgeBaseChunk chunk =
                new KnowledgeBaseChunk(UUID.randomUUID(), UUID.randomUUID(), 0, "Art. 1º ...", null);

        assertThat(chunk.isIndexed()).isFalse();

        KnowledgeBaseChunk indexed = chunk.withEmbedding(Embedding.of(new float[] {1.0f}));

        assertThat(indexed.isIndexed()).isTrue();
        assertThat(indexed.content()).isEqualTo(chunk.content());
        assertThat(indexed.toString()).contains("indexed=true").doesNotContain("Art. 1º");
    }

    @Test
    @DisplayName("um trecho vazio ou com índice negativo é recusado")
    void shouldRejectInvalidChunk() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new KnowledgeBaseChunk(id, id, 0, "   ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
        assertThatThrownBy(() -> new KnowledgeBaseChunk(id, id, -1, "texto", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunkIndex");
    }

    @Test
    @DisplayName("um trecho de texto sem conteúdo é recusado")
    void shouldRejectInvalidTextChunk() {
        assertThatThrownBy(() -> new TextChunk(0, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChunk(-1, "texto")).isInstanceOf(IllegalArgumentException.class);
    }
}
