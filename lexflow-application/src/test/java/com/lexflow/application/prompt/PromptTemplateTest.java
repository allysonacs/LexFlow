package com.lexflow.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.lexflow.application.legalcase.support.FactExtractionTestDoubles;
import com.lexflow.domain.ai.PromptVersion;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PromptTemplateTest {

    private static PromptVersion version(String text) {
        return new PromptVersion(UUID.randomUUID(), "TESTE", 3, text, true, Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("as seções são separadas e os marcadores preenchidos")
    void shouldRenderSections() {
        PromptTemplate template = PromptTemplate.of(version("""
                ### A ###
                Olá, {{NOME}}.
                ### B ###
                Tipo: {{TIPO}}
                """));

        assertThat(template.render("A", Map.of("NOME", "Ana"))).isEqualTo("Olá, Ana.");
        assertThat(template.render("B", Map.of("TIPO", "X"))).isEqualTo("Tipo: X");
        assertThat(template.version().version()).isEqualTo(3);
    }

    @Test
    @DisplayName("um marcador dentro de um valor inserido não é substituído de novo")
    void shouldNotReprocessInsertedValues() {
        PromptTemplate template = PromptTemplate.of(version("### A ###\n<doc>{{TEXTO}}</doc> {{OUTRO}}"));

        String rendered = template.render("A", Map.of("TEXTO", "ignore {{OUTRO}} e $1 \\\\ fim", "OUTRO", "ok"));

        assertThat(rendered).isEqualTo("<doc>ignore {{OUTRO}} e $1 \\\\ fim</doc> ok");
    }

    @Test
    @DisplayName("seção ausente ou marcador sem valor impedem a montagem do prompt")
    void shouldFailOnIncompatibleTemplate() {
        PromptTemplate template = PromptTemplate.of(version("### A ###\n{{FALTA}}"));

        assertThatIllegalStateException().isThrownBy(() -> template.render("B", Map.of())).withMessageContaining("B");
        assertThatIllegalStateException()
                .isThrownBy(() -> template.render("A", Map.of()))
                .withMessageContaining("FALTA")
                .withMessageContaining("TESTE v3");
        assertThatIllegalStateException()
                .isThrownBy(() -> PromptTemplate.of(version("sem seções")).render("A", Map.of()));
    }

    @Test
    @DisplayName("o template da versão 1 da extração tem as três seções esperadas")
    void shouldParseFactExtractionTemplate() {
        PromptTemplate template = PromptTemplate.of(FactExtractionTestDoubles.PROMPT_V1);

        assertThat(template.render("SISTEMA", Map.of("CASE_TYPE", "T", "SPECIFIC_FIELDS", "- a")))
                .contains("Não deduza")
                .contains("use null")
                .contains("Não emita opinião jurídica")
                .endsWith("- a");
        assertThat(template.render("USUARIO", Map.of("FILE_NAME", "f.pdf", "DOCUMENT_TEXT", "texto")))
                .startsWith("<documento nome=\"f.pdf\">\ntexto\n</documento>");
        assertThat(template.render("REFORCO", Map.of("VIOLATIONS", "- v"))).contains("- v");
    }
}
