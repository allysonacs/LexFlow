package com.lexflow.application.prompt;

import com.lexflow.domain.ai.PromptVersion;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template de prompt dividido em seções e com marcadores {@code {{NOME}}}.
 *
 * <p>O texto de uma versão é organizado em seções iniciadas por uma linha {@code ### NOME ###}. Os
 * marcadores são substituídos em uma única passada: um valor inserido — o texto de um documento, por
 * exemplo — nunca é lido de novo como template, então um {@code {{...}}} dentro dele fica intacto.
 */
public final class PromptTemplate {

    private static final Pattern SECTION = Pattern.compile("^### ([A-Z_]+) ###\\s*$", Pattern.MULTILINE);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z_]+)}}");

    private final PromptVersion version;
    private final Map<String, String> sections;

    private PromptTemplate(PromptVersion version, Map<String, String> sections) {
        this.version = version;
        this.sections = sections;
    }

    /** Separa as seções do texto da versão. */
    public static PromptTemplate of(PromptVersion version) {
        Objects.requireNonNull(version, "version não pode ser nulo");
        Map<String, String> sections = new LinkedHashMap<>();
        Matcher matcher = SECTION.matcher(version.templateText());
        String currentName = null;
        int currentStart = 0;
        while (matcher.find()) {
            if (currentName != null) {
                sections.put(currentName, version.templateText().substring(currentStart, matcher.start()).strip());
            }
            currentName = matcher.group(1);
            currentStart = matcher.end();
        }
        if (currentName != null) {
            sections.put(currentName, version.templateText().substring(currentStart).strip());
        }
        return new PromptTemplate(version, sections);
    }

    public PromptVersion version() {
        return version;
    }

    /**
     * Preenche uma seção.
     *
     * @throws IllegalStateException se a seção não existir ou se sobrar marcador sem valor — um
     *     template incompatível com o código não pode gerar um prompt pela metade
     */
    public String render(String sectionName, Map<String, String> values) {
        String section = sections.get(sectionName);
        if (section == null) {
            throw new IllegalStateException("seção %s ausente em %s".formatted(sectionName, version.label()));
        }
        Matcher matcher = PLACEHOLDER.matcher(section);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) {
                throw new IllegalStateException(
                        "marcador {{%s}} sem valor em %s".formatted(matcher.group(1), version.label()));
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
