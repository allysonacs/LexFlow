package com.lexflow.domain.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentTextContentTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    private static final Document DOCUMENT = new Document(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "contrato.pdf",
            "legal-cases/x/contrato.pdf",
            "application/pdf",
            Sha256Checksum.ofContent(new byte[] {1}),
            NOW);

    @Test
    @DisplayName("texto extraído fica associado ao documento e à demanda")
    void shouldRecordExtractedText() {
        DocumentTextContent content = DocumentTextContent.extracted(
                UUID.randomUUID(), DOCUMENT, "  Cláusula primeira\n", TextExtractionMethod.NATIVE_TEXT, NOW);

        assertThat(content.status()).isEqualTo(TextExtractionStatus.EXTRACTED);
        assertThat(content.documentId()).isEqualTo(DOCUMENT.id());
        assertThat(content.legalCaseId()).isEqualTo(DOCUMENT.legalCaseId());
        assertThat(content.content()).isEqualTo("Cláusula primeira");
        assertThat(content.characterCount()).isEqualTo(17);
        assertThat(content.hasText()).isTrue();
        assertThat(content.failureReason()).isNull();
    }

    @Test
    @DisplayName("o caractere nulo é removido, porque o PostgreSQL o recusa")
    void shouldStripNullCharacters() {
        String withNull = "abc" + (char) 0 + "def";

        DocumentTextContent content =
                DocumentTextContent.extracted(UUID.randomUUID(), DOCUMENT, withNull, TextExtractionMethod.OCR, NOW);

        assertThat(content.content()).isEqualTo("abcdef");
    }

    @Test
    @DisplayName("texto em branco vira NO_TEXT_FOUND, com conteúdo vazio")
    void shouldRecordNoTextFound() {
        DocumentTextContent blank =
                DocumentTextContent.extracted(UUID.randomUUID(), DOCUMENT, " \n\t", TextExtractionMethod.OCR, NOW);
        DocumentTextContent absent =
                DocumentTextContent.extracted(UUID.randomUUID(), DOCUMENT, null, TextExtractionMethod.OCR, NOW);

        assertThat(blank.status()).isEqualTo(TextExtractionStatus.NO_TEXT_FOUND);
        assertThat(blank.content()).isEmpty();
        assertThat(blank.hasText()).isFalse();
        assertThat(absent.status()).isEqualTo(TextExtractionStatus.NO_TEXT_FOUND);
    }

    @Test
    @DisplayName("falha registra o motivo, truncado ao tamanho da coluna, e nenhum texto")
    void shouldRecordFailure() {
        DocumentTextContent failed =
                DocumentTextContent.failed(UUID.randomUUID(), DOCUMENT, "x".repeat(600), NOW);
        DocumentTextContent withoutReason = DocumentTextContent.failed(UUID.randomUUID(), DOCUMENT, "  ", NOW);

        assertThat(failed.status()).isEqualTo(TextExtractionStatus.FAILED);
        assertThat(failed.content()).isNull();
        assertThat(failed.method()).isNull();
        assertThat(failed.characterCount()).isZero();
        assertThat(failed.failureReason()).hasSize(DocumentTextContent.FAILURE_REASON_MAX_LENGTH);
        assertThat(withoutReason.failureReason()).isEqualTo("motivo não informado");
    }

    @Test
    @DisplayName("o toString não expõe o conteúdo do documento")
    void shouldNotLeakContentInToString() {
        DocumentTextContent content = DocumentTextContent.extracted(
                UUID.randomUUID(), DOCUMENT, "CPF 123.456.789-00", TextExtractionMethod.OCR, NOW);

        assertThat(content.toString()).doesNotContain("123.456.789-00").contains("characters=18");
    }

    @Test
    @DisplayName("combinações incoerentes de status, texto e método são recusadas")
    void shouldRejectInconsistentState() {
        UUID id = UUID.randomUUID();
        UUID documentId = DOCUMENT.id();
        UUID caseId = DOCUMENT.legalCaseId();

        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new DocumentTextContent(
                id, documentId, caseId, "", TextExtractionMethod.OCR, TextExtractionStatus.EXTRACTED, null, NOW));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new DocumentTextContent(
                id, documentId, caseId, "texto", null, TextExtractionStatus.EXTRACTED, null, NOW));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new DocumentTextContent(
                id, documentId, caseId, "texto", TextExtractionMethod.OCR, TextExtractionStatus.EXTRACTED, "erro", NOW));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new DocumentTextContent(
                id, documentId, caseId, "texto", TextExtractionMethod.OCR, TextExtractionStatus.NO_TEXT_FOUND, null, NOW));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new DocumentTextContent(
                id, documentId, caseId, "", null, TextExtractionStatus.NO_TEXT_FOUND, null, NOW));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new DocumentTextContent(
                id, documentId, caseId, "", TextExtractionMethod.OCR, TextExtractionStatus.NO_TEXT_FOUND, "erro", NOW));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new DocumentTextContent(
                id, documentId, caseId, "texto", null, TextExtractionStatus.FAILED, "erro", NOW));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new DocumentTextContent(
                id, documentId, caseId, null, null, TextExtractionStatus.FAILED, null, NOW));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new DocumentTextContent(
                id, documentId, caseId, null, null, TextExtractionStatus.FAILED, "x".repeat(501), NOW));
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new DocumentTextContent(
                id, documentId, caseId, null, null, null, "erro", NOW));
    }
}
