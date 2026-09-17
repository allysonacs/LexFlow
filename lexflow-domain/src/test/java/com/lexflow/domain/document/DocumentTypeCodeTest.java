package com.lexflow.domain.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.domain.exception.DomainException;
import com.lexflow.domain.exception.InvalidDocumentTypeException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentTypeCodeTest {

    @Test
    @DisplayName("o código é normalizado para UPPER_SNAKE_CASE")
    void shouldNormalize() {
        assertThat(DocumentTypeCode.normalize("  contract_draft ")).isEqualTo("CONTRACT_DRAFT");
        assertThat(DocumentTypeCode.normalize("CNPJ2")).isEqualTo("CNPJ2");
        assertThat(DocumentTypeCode.normalizeOptional("  ")).isNull();
        assertThat(DocumentTypeCode.normalizeOptional(null)).isNull();
        assertThat(DocumentTypeCode.normalizeOptional("minuta")).isEqualTo("MINUTA");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "minuta do contrato", "1_DOCUMENTO", "_DRAFT", "CONTRATO-SOCIAL", "PETIÇÃO"})
    @DisplayName("códigos fora do formato são recusados como erro de negócio")
    void shouldRejectInvalidCodes(String code) {
        assertThatExceptionOfType(InvalidDocumentTypeException.class)
                .isThrownBy(() -> DocumentTypeCode.normalize(code))
                .isInstanceOf(DomainException.class)
                .withMessageContaining("Tipo de documento inválido");
    }

    @Test
    @DisplayName("código acima do tamanho da coluna é recusado")
    void shouldRejectTooLongCode() {
        assertThat(DocumentTypeCode.normalize("A".repeat(DocumentTypeCode.MAX_LENGTH))).hasSize(100);
        assertThatExceptionOfType(InvalidDocumentTypeException.class)
                .isThrownBy(() -> DocumentTypeCode.normalize("A".repeat(DocumentTypeCode.MAX_LENGTH + 1)));
    }

    @Test
    @DisplayName("o documento guarda o tipo normalizado e sabe dizer se é de um tipo")
    void shouldNormalizeDocumentType() {
        Document document = new Document(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "minuta.pdf",
                "legal-cases/x/minuta.pdf",
                "application/pdf",
                Sha256Checksum.ofContent(new byte[] {1}),
                Instant.parse("2026-03-10T12:00:00Z"),
                "contract_draft");

        assertThat(document.documentType()).isEqualTo("CONTRACT_DRAFT");
        assertThat(document.isOfType("CONTRACT_DRAFT")).isTrue();
        assertThat(document.isOfType("FINANCIAL_OPINION")).isFalse();
        assertThat(new Document(
                        document.id(), document.legalCaseId(), "a.pdf", "p", "application/pdf",
                        document.checksum(), document.uploadedAt())
                .isOfType("CONTRACT_DRAFT"))
                .isFalse();
    }
}
