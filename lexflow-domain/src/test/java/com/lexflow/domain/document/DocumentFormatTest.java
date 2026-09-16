package com.lexflow.domain.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.domain.exception.UnsupportedDocumentFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentFormatTest {

    @ParameterizedTest
    @CsvSource({
        "contrato.pdf,PDF",
        "CONTRATO.PDF,PDF",
        "minuta.docx,DOCX",
        "digitalizacao.jpg,JPEG",
        "digitalizacao.jpeg,JPEG",
        "print.png,PNG"
    })
    @DisplayName("o formato é resolvido pela extensão, sem depender de maiúsculas")
    void shouldResolveFormatByExtension(String fileName, DocumentFormat expected) {
        assertThat(DocumentFormat.ofFileName(fileName)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"planilha.xlsx", "arquivo.exe", "sem-extensao", "termina-com-ponto.", ""})
    @DisplayName("formatos fora da lista são recusados")
    void shouldRejectUnsupportedFormats(String fileName) {
        assertThatExceptionOfType(UnsupportedDocumentFormatException.class)
                .isThrownBy(() -> DocumentFormat.ofFileName(fileName))
                .withMessageContaining("Formato de arquivo não aceito");
    }

    @Test
    @DisplayName("um nome de arquivo nulo é recusado como formato inválido")
    void shouldRejectNullFileName() {
        assertThatExceptionOfType(UnsupportedDocumentFormatException.class)
                .isThrownBy(() -> DocumentFormat.ofFileName(null));
    }

    @Test
    @DisplayName("mime type ausente ou genérico é aceito: quem manda é a extensão")
    void shouldAcceptGenericMimeTypes() {
        assertThat(DocumentFormat.PDF.acceptsMimeType(null)).isTrue();
        assertThat(DocumentFormat.PDF.acceptsMimeType("  ")).isTrue();
        assertThat(DocumentFormat.PDF.acceptsMimeType("application/octet-stream")).isTrue();
    }

    @Test
    @DisplayName("mime type compatível é aceito mesmo com parâmetros e em maiúsculas")
    void shouldAcceptMatchingMimeType() {
        assertThat(DocumentFormat.PDF.acceptsMimeType("APPLICATION/PDF")).isTrue();
        assertThat(DocumentFormat.PNG.acceptsMimeType("image/png; charset=binary")).isTrue();
        assertThat(DocumentFormat.JPEG.acceptsMimeType("image/jpg")).isTrue();
    }

    @Test
    @DisplayName("mime type que contradiz a extensão é recusado")
    void shouldRejectMismatchingMimeType() {
        assertThat(DocumentFormat.PDF.acceptsMimeType("image/png")).isFalse();
    }

    @Test
    @DisplayName("o mime type canônico é o que vai para o banco, não o declarado pelo cliente")
    void shouldExposeCanonicalMimeType() {
        assertThat(DocumentFormat.JPEG.canonicalMimeType()).isEqualTo("image/jpeg");
        assertThat(DocumentFormat.DOCX.canonicalMimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    @DisplayName("as extensões aceitas compõem a mensagem de erro")
    void shouldListSupportedExtensions() {
        assertThat(DocumentFormat.supportedExtensions()).containsExactly("docx", "jpeg", "jpg", "pdf", "png");
    }
}
