package com.lexflow.application.document;

import com.lexflow.domain.document.TextExtractionMethod;
import java.util.Objects;

/**
 * Texto devolvido por um {@link DocumentTextExtractor}, ainda sem saneamento.
 *
 * <p>O {@code toString} omite o texto: ele é conteúdo de documento e não pode ir para o log (seção 12).
 *
 * @param text texto bruto; pode ser vazio quando o arquivo não tem texto reconhecível
 */
public record ExtractedText(String text, TextExtractionMethod method) {

    public ExtractedText {
        Objects.requireNonNull(text, "text não pode ser nulo");
        Objects.requireNonNull(method, "method não pode ser nulo");
    }

    @Override
    public String toString() {
        return "ExtractedText[method=%s, characters=%d]".formatted(method, text.length());
    }
}
