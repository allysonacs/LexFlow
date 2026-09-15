package com.lexflow.domain.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentTest {

    private static final Instant UPLOADED_AT = Instant.parse("2026-01-10T12:00:00Z");
    private static final String CHECKSUM = "a".repeat(64);
    private static final UUID LEGAL_CASE_ID = UUID.randomUUID();

    private Document document(String fileName, String checksum, UUID legalCaseId) {
        return new Document(
                UUID.randomUUID(),
                legalCaseId,
                fileName,
                "legal-cases/%s/%s".formatted(legalCaseId, fileName),
                "application/pdf",
                Sha256Checksum.of(checksum),
                UPLOADED_AT);
    }

    @Test
    void shouldCreateDocument() {
        Document document = document("contrato.pdf", CHECKSUM, LEGAL_CASE_ID);

        assertThat(document.fileName()).isEqualTo("contrato.pdf");
        assertThat(document.checksum().value()).isEqualTo(CHECKSUM);
    }

    @Test
    @DisplayName("mesmo checksum na mesma demanda indica reenvio do mesmo arquivo")
    void shouldDetectDuplicateContent() {
        Document original = document("contrato.pdf", CHECKSUM, LEGAL_CASE_ID);
        Document resent = document("contrato-copia.pdf", CHECKSUM, LEGAL_CASE_ID);
        Document otherContent = document("contrato.pdf", "b".repeat(64), LEGAL_CASE_ID);
        Document otherCase = document("contrato.pdf", CHECKSUM, UUID.randomUUID());

        assertThat(original.hasSameContentAs(resent)).isTrue();
        assertThat(original.hasSameContentAs(otherContent)).isFalse();
        assertThat(original.hasSameContentAs(otherCase)).isFalse();
        assertThat(original.hasSameContentAs(null)).isFalse();
    }

    @Test
    void shouldValidateRequiredMetadata() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> document(" ", CHECKSUM, LEGAL_CASE_ID))
                .withMessageContaining("fileName");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Document(
                        UUID.randomUUID(),
                        LEGAL_CASE_ID,
                        "contrato.pdf",
                        " ",
                        "application/pdf",
                        Sha256Checksum.of(CHECKSUM),
                        UPLOADED_AT))
                .withMessageContaining("storagePath");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Document(
                        UUID.randomUUID(),
                        LEGAL_CASE_ID,
                        "contrato.pdf",
                        "path",
                        null,
                        Sha256Checksum.of(CHECKSUM),
                        UPLOADED_AT))
                .withMessageContaining("mimeType");

        assertThatNullPointerException()
                .isThrownBy(() -> new Document(
                        UUID.randomUUID(),
                        LEGAL_CASE_ID,
                        "contrato.pdf",
                        "path",
                        "application/pdf",
                        null,
                        UPLOADED_AT));
    }
}
