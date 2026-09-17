package com.lexflow.domain.document;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Texto extraído de um documento, correspondente a uma linha de {@code document_text_contents}.
 *
 * <p>É a matéria-prima das etapas seguintes (extração de fatos, Prompt 11). Um documento que não pôde
 * ser lido também gera um registro, com status {@link TextExtractionStatus#FAILED}: assim a falha fica
 * visível para o responsável humano, em vez de o documento simplesmente sumir do pipeline.
 *
 * <p>O conteúdo é dado sensível (seção 12): {@link #toString()} não o inclui, para que ele não vaze
 * para o log por descuido.
 *
 * @param content texto extraído; vazio em {@link TextExtractionStatus#NO_TEXT_FOUND} e nulo em
 *     {@link TextExtractionStatus#FAILED}
 * @param method nulo apenas em {@link TextExtractionStatus#FAILED}
 * @param failureReason motivo técnico resumido; preenchido apenas em {@link TextExtractionStatus#FAILED}
 */
public record DocumentTextContent(
        UUID id,
        UUID documentId,
        UUID legalCaseId,
        String content,
        TextExtractionMethod method,
        TextExtractionStatus status,
        String failureReason,
        Instant extractedAt) {

    /** Tamanho máximo do motivo de falha, compatível com a coluna. */
    public static final int FAILURE_REASON_MAX_LENGTH = 500;

    /** Caractere nulo: alguns PDFs o trazem, e o PostgreSQL o recusa em colunas de texto. */
    private static final String NULL_CHARACTER = String.valueOf((char) 0);

    public DocumentTextContent {
        Objects.requireNonNull(id, "id não pode ser nulo");
        Objects.requireNonNull(documentId, "documentId não pode ser nulo");
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        Objects.requireNonNull(status, "status não pode ser nulo");
        Objects.requireNonNull(extractedAt, "extractedAt não pode ser nulo");
        switch (status) {
            case EXTRACTED -> {
                require(content != null && !content.isBlank(), "EXTRACTED exige conteúdo");
                require(method != null, "EXTRACTED exige o método de extração");
                require(failureReason == null, "EXTRACTED não tem motivo de falha");
            }
            case NO_TEXT_FOUND -> {
                require(content != null && content.isEmpty(), "NO_TEXT_FOUND exige conteúdo vazio");
                require(method != null, "NO_TEXT_FOUND exige o método de extração");
                require(failureReason == null, "NO_TEXT_FOUND não tem motivo de falha");
            }
            case FAILED -> {
                require(content == null && method == null, "FAILED não tem conteúdo nem método");
                require(failureReason != null && !failureReason.isBlank(), "FAILED exige o motivo");
                require(failureReason.length() <= FAILURE_REASON_MAX_LENGTH, "motivo de falha longo demais");
            }
        }
    }

    /**
     * Registra o texto obtido de um documento.
     *
     * <p>O texto é saneado antes: o caractere nulo é removido e as bordas em branco são descartadas.
     * Se nada sobrar, o registro sai como {@link TextExtractionStatus#NO_TEXT_FOUND}.
     */
    public static DocumentTextContent extracted(
            UUID id, Document document, String rawText, TextExtractionMethod method, Instant extractedAt) {
        Objects.requireNonNull(document, "document não pode ser nulo");
        String text = sanitize(rawText);
        return new DocumentTextContent(
                id,
                document.id(),
                document.legalCaseId(),
                text,
                method,
                text.isEmpty() ? TextExtractionStatus.NO_TEXT_FOUND : TextExtractionStatus.EXTRACTED,
                null,
                extractedAt);
    }

    /** Registra que o documento não pôde ser lido. O motivo é truncado ao tamanho da coluna. */
    public static DocumentTextContent failed(UUID id, Document document, String reason, Instant extractedAt) {
        Objects.requireNonNull(document, "document não pode ser nulo");
        String safeReason = reason == null || reason.isBlank() ? "motivo não informado" : reason.strip();
        if (safeReason.length() > FAILURE_REASON_MAX_LENGTH) {
            safeReason = safeReason.substring(0, FAILURE_REASON_MAX_LENGTH);
        }
        return new DocumentTextContent(
                id,
                document.id(),
                document.legalCaseId(),
                null,
                null,
                TextExtractionStatus.FAILED,
                safeReason,
                extractedAt);
    }

    /** Quantidade de caracteres do texto; zero quando não há texto. */
    public int characterCount() {
        return content == null ? 0 : content.length();
    }

    /** Indica se há texto para as etapas seguintes. */
    public boolean hasText() {
        return status == TextExtractionStatus.EXTRACTED;
    }

    @Override
    public String toString() {
        return "DocumentTextContent[id=%s, documentId=%s, status=%s, method=%s, characters=%d]"
                .formatted(id, documentId, status, method, characterCount());
    }

    private static String sanitize(String rawText) {
        return rawText == null ? "" : rawText.replace(NULL_CHARACTER, "").strip();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
