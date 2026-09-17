package com.lexflow.application.legalcase.support;

import com.lexflow.application.document.DocumentTextContentRepository;
import com.lexflow.application.document.DocumentTextExtractor;
import com.lexflow.application.document.ExtractedText;
import com.lexflow.domain.document.DocumentFormat;
import com.lexflow.domain.document.DocumentTextContent;
import com.lexflow.domain.document.TextExtractionMethod;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/** Dublês das portas de extração de texto. */
public final class ExtractionTestDoubles {

    private ExtractionTestDoubles() {
        // classe utilitária
    }

    /** Texto extraído em memória, com a unicidade por documento que o banco garante. */
    public static final class InMemoryDocumentTextContentRepository implements DocumentTextContentRepository {

        private final Map<UUID, DocumentTextContent> byDocument = new LinkedHashMap<>();

        @Override
        public void save(DocumentTextContent textContent) {
            if (byDocument.putIfAbsent(textContent.documentId(), textContent) != null) {
                throw new IllegalStateException("documento já tem texto: " + textContent.documentId());
            }
        }

        @Override
        public List<DocumentTextContent> findByLegalCaseId(UUID legalCaseId) {
            return byDocument.values().stream()
                    .filter(content -> content.legalCaseId().equals(legalCaseId))
                    .toList();
        }

        public List<DocumentTextContent> all() {
            return List.copyOf(byDocument.values());
        }
    }

    /**
     * Extrator programável: por padrão devolve o próprio conteúdo como texto, pelo método informado
     * na resposta; cada teste pode trocar o comportamento.
     */
    public static final class ScriptedTextExtractor implements DocumentTextExtractor {

        private BiFunction<DocumentFormat, byte[], ExtractedText> behavior = ScriptedTextExtractor::echo;
        private final List<DocumentFormat> calls = new ArrayList<>();

        @Override
        public ExtractedText extract(DocumentFormat format, byte[] content) {
            calls.add(format);
            return behavior.apply(format, content);
        }

        public void willAnswer(BiFunction<DocumentFormat, byte[], ExtractedText> behavior) {
            this.behavior = behavior;
        }

        /** Formatos recebidos, na ordem das chamadas. */
        public List<DocumentFormat> calls() {
            return List.copyOf(calls);
        }

        public static ExtractedText echo(DocumentFormat format, byte[] content) {
            TextExtractionMethod method = switch (format) {
                case PDF, DOCX -> TextExtractionMethod.NATIVE_TEXT;
                case JPEG, PNG -> TextExtractionMethod.OCR;
            };
            return new ExtractedText(new String(content, StandardCharsets.UTF_8), method);
        }
    }
}
