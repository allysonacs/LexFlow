package com.lexflow.infrastructure.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.application.document.ExtractedText;
import com.lexflow.application.exception.DocumentTextExtractionException;
import com.lexflow.application.exception.UnreadableDocumentException;
import com.lexflow.domain.document.DocumentFormat;
import com.lexflow.domain.document.TextExtractionMethod;
import com.lexflow.infrastructure.testsupport.DocumentFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Extração de texto contra arquivos reais, sem Spring e sem Docker.
 *
 * <p>Os testes de OCR <strong>exigem o Tesseract instalado</strong>, com o pacote de idioma
 * {@code por} ({@code brew install tesseract tesseract-lang} no macOS; {@code apt-get install
 * tesseract-ocr tesseract-ocr-por} no Debian/Ubuntu). A ausência dele faz o teste falhar, e não ser
 * pulado: um OCR que deixou de funcionar precisa aparecer no build.
 *
 * <p>As fixtures estão descritas em {@link DocumentFixtures}.
 */
class TikaDocumentTextExtractorTest {

    private static TikaDocumentTextExtractor extractor;

    @BeforeAll
    static void setUp() {
        extractor = new TikaDocumentTextExtractor(TextExtractionProperties.defaults());
        assertThat(extractor.isOcrAvailable())
                .as("Tesseract precisa estar instalado para os testes de OCR (ver Javadoc da classe)")
                .isTrue();
    }

    private static byte[] fixture(String fileName) {
        return DocumentFixtures.read(fileName);
    }

    /** Espaços colapsados, para que a quebra de linha do OCR não atrapalhe a comparação. */
    private static String normalized(ExtractedText extracted) {
        return extracted.text().replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("PDF com camada de texto é lido diretamente, sem OCR, preservando acentos")
    void shouldExtractNativePdfText() {
        ExtractedText extracted = extractor.extract(DocumentFormat.PDF, fixture("contrato-texto-nativo.pdf"));

        assertThat(extracted.method()).isEqualTo(TextExtractionMethod.NATIVE_TEXT);
        assertThat(normalized(extracted))
                .contains("CONTRATO DE PRESTAÇÃO DE SERVIÇOS")
                .contains("Empresa Exemplo Ltda, CNPJ 12.345.678/0001-90")
                .contains("Valor mensal: R$ 15.000,00")
                .contains("Vigência: 12 meses");
    }

    @Test
    @DisplayName("imagem PNG passa pelo OCR")
    void shouldExtractTextFromPngImage() {
        ExtractedText extracted = extractor.extract(DocumentFormat.PNG, fixture("termo-de-acordo.png"));

        assertThat(extracted.method()).isEqualTo(TextExtractionMethod.OCR);
        assertThat(normalized(extracted))
                .contains("TERMO DE ACORDO")
                .contains("Joana Pereira")
                .contains("R$ 8.500,00");
    }

    @Test
    @DisplayName("imagem JPEG passa pelo OCR")
    void shouldExtractTextFromJpegImage() {
        ExtractedText extracted = extractor.extract(DocumentFormat.JPEG, fixture("proposta-comercial.jpg"));

        assertThat(extracted.method()).isEqualTo(TextExtractionMethod.OCR);
        assertThat(normalized(extracted)).contains("PROPOSTA COMERCIAL").contains("R$ 42.000,00");
    }

    @Test
    @DisplayName("PDF digitalizado, sem camada de texto, é renderizado e passa pelo OCR")
    void shouldFallBackToOcrForScannedPdf() {
        ExtractedText extracted = extractor.extract(DocumentFormat.PDF, fixture("peticao-digitalizada.pdf"));

        assertThat(extracted.method()).isEqualTo(TextExtractionMethod.OCR);
        assertThat(normalized(extracted))
                .contains("PETICAO DE ENCERRAMENTO")
                .contains("0001234-56.2024.8.26.0100")
                .contains("arquivamento definitivo");
    }

    @Test
    @DisplayName("DOCX é lido pela camada de texto")
    void shouldExtractDocxText() {
        ExtractedText extracted = extractor.extract(DocumentFormat.DOCX, fixture("minuta-aditivo.docx"));

        assertThat(extracted.method()).isEqualTo(TextExtractionMethod.NATIVE_TEXT);
        assertThat(normalized(extracted))
                .contains("MINUTA DO TERMO ADITIVO Nº 2")
                .contains("Cláusula primeira: prorrogação do prazo");
    }

    @Test
    @DisplayName("arquivo que não é PDF de verdade é recusado como ilegível, não como erro de ambiente")
    void shouldRejectCorruptedPdfAsUnreadable() {
        byte[] notAPdf = "isto não é um pdf".getBytes(StandardCharsets.UTF_8);

        assertThatExceptionOfType(UnreadableDocumentException.class)
                .isThrownBy(() -> extractor.extract(DocumentFormat.PDF, notAPdf))
                .withMessageContaining("PDF");
    }

    @Test
    @DisplayName("DOCX corrompido é recusado como ilegível")
    void shouldRejectCorruptedDocxAsUnreadable() {
        byte[] notADocx = "PK quebrado".getBytes(StandardCharsets.UTF_8);

        assertThatExceptionOfType(UnreadableDocumentException.class)
                .isThrownBy(() -> extractor.extract(DocumentFormat.DOCX, notADocx));
    }

    @Test
    @DisplayName("sem Tesseract, documento que precisa de OCR falha como erro de ambiente")
    void shouldFailWithEnvironmentErrorWhenTesseractIsMissing(@TempDir Path directoryWithoutTesseract) {
        TikaDocumentTextExtractor withoutOcr = new TikaDocumentTextExtractor(new TextExtractionProperties(
                "por", directoryWithoutTesseract.toString(), Duration.ofSeconds(10), 300, 10));

        assertThat(withoutOcr.isOcrAvailable()).isFalse();
        assertThatExceptionOfType(DocumentTextExtractionException.class)
                .isThrownBy(() -> withoutOcr.extract(DocumentFormat.PNG, fixture("termo-de-acordo.png")))
                .withMessageContaining("Tesseract");
        assertThatExceptionOfType(DocumentTextExtractionException.class)
                .isThrownBy(() -> withoutOcr.extract(DocumentFormat.PDF, fixture("peticao-digitalizada.pdf")));
        // PDF com texto continua funcionando: a falta do OCR não derruba o que não depende dele.
        assertThat(withoutOcr.extract(DocumentFormat.PDF, fixture("contrato-texto-nativo.pdf")).method())
                .isEqualTo(TextExtractionMethod.NATIVE_TEXT);
    }

    @Test
    @DisplayName("caminho do Tesseract inexistente impede a inicialização")
    void shouldFailFastWhenTesseractPathDoesNotExist() {
        TextExtractionProperties properties =
                new TextExtractionProperties("por", "/caminho/que/nao/existe", null, null, null);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new TikaDocumentTextExtractor(properties))
                .withMessageContaining("Tesseract");
    }

    @Test
    @DisplayName("configuração inválida é recusada na inicialização")
    void shouldRejectInvalidProperties() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new TextExtractionProperties(null, null, Duration.ZERO, null, null));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new TextExtractionProperties(null, null, null, 10, null));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new TextExtractionProperties(null, null, null, null, 0));
        assertThat(TextExtractionProperties.defaults())
                .satisfies(defaults -> {
                    assertThat(defaults.ocrLanguage()).isEqualTo("por");
                    assertThat(defaults.ocrDpi()).isEqualTo(300);
                    assertThat(defaults.ocrTimeout()).isEqualTo(Duration.ofMinutes(2));
                });
    }
}
