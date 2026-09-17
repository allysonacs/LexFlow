package com.lexflow.application.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.application.exception.DocumentNotFoundInStorageException;
import com.lexflow.application.exception.DocumentTextExtractionException;
import com.lexflow.application.exception.UnreadableDocumentException;
import com.lexflow.application.legalcase.support.ExtractionTestDoubles.InMemoryDocumentTextContentRepository;
import com.lexflow.application.legalcase.support.ExtractionTestDoubles.ScriptedTextExtractor;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryDocumentRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.RecordingDocumentStorage;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.SequentialIdGenerator;
import com.lexflow.domain.document.Document;
import com.lexflow.domain.document.DocumentFormat;
import com.lexflow.domain.document.DocumentTextContent;
import com.lexflow.domain.document.Sha256Checksum;
import com.lexflow.domain.document.TextExtractionMethod;
import com.lexflow.domain.document.TextExtractionStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExtractDocumentTextServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final UUID CASE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private InMemoryDocumentRepository documentRepository;
    private InMemoryDocumentTextContentRepository textContentRepository;
    private RecordingDocumentStorage storage;
    private ScriptedTextExtractor extractor;
    private ExtractDocumentTextService service;

    @BeforeEach
    void setUp() {
        documentRepository = new InMemoryDocumentRepository();
        textContentRepository = new InMemoryDocumentTextContentRepository();
        storage = new RecordingDocumentStorage();
        extractor = new ScriptedTextExtractor();
        service = new ExtractDocumentTextService(
                documentRepository,
                textContentRepository,
                storage,
                extractor,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SequentialIdGenerator());
    }

    /** Grava o binário no storage em memória e o metadado no repositório, como a ingestão faria. */
    private Document givenDocument(String fileName, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        DocumentFormat format = DocumentFormat.ofFileName(fileName);
        Sha256Checksum checksum = Sha256Checksum.ofContent(bytes);
        String path = storage.store(CASE_ID, format, checksum, new DocumentUpload(fileName, null, bytes))
                .storagePath();
        Document document = new Document(
                UUID.randomUUID(), CASE_ID, fileName, path, format.canonicalMimeType(), checksum, NOW);
        documentRepository.saveAll(List.of(document));
        return document;
    }

    @Test
    @DisplayName("extrai e grava o texto de cada documento, com o método usado")
    void shouldExtractEveryDocument() {
        Document pdf = givenDocument("contrato.pdf", "Cláusula primeira");
        Document png = givenDocument("procuracao.png", "Outorgante: Empresa");

        List<DocumentTextContent> result = service.extractPending(CASE_ID);

        assertThat(result).extracting(DocumentTextContent::documentId).containsExactly(pdf.id(), png.id());
        assertThat(result).allSatisfy(content -> {
            assertThat(content.status()).isEqualTo(TextExtractionStatus.EXTRACTED);
            assertThat(content.legalCaseId()).isEqualTo(CASE_ID);
            assertThat(content.extractedAt()).isEqualTo(NOW);
        });
        assertThat(result.get(0).method()).isEqualTo(TextExtractionMethod.NATIVE_TEXT);
        assertThat(result.get(0).content()).isEqualTo("Cláusula primeira");
        assertThat(result.get(1).method()).isEqualTo(TextExtractionMethod.OCR);
        assertThat(textContentRepository.all()).hasSize(2);
        assertThat(extractor.calls()).containsExactly(DocumentFormat.PDF, DocumentFormat.PNG);
    }

    @Test
    @DisplayName("documento ilegível vira registro FAILED, e os demais seguem")
    void shouldRecordUnreadableDocumentAndContinue() {
        givenDocument("corrompido.pdf", "lixo");
        givenDocument("foto.jpg", "texto da foto");
        extractor.willAnswer((format, content) -> {
            if (format == DocumentFormat.PDF) {
                throw new UnreadableDocumentException("Não foi possível interpretar o documento PDF");
            }
            return ScriptedTextExtractor.echo(format, content);
        });

        List<DocumentTextContent> result = service.extractPending(CASE_ID);

        assertThat(result).extracting(DocumentTextContent::status)
                .containsExactly(TextExtractionStatus.FAILED, TextExtractionStatus.EXTRACTED);
        assertThat(result.get(0).failureReason()).contains("interpretar");
        assertThat(textContentRepository.all()).hasSize(2);
    }

    @Test
    @DisplayName("documento sem texto reconhecível vira NO_TEXT_FOUND")
    void shouldRecordNoTextFound() {
        givenDocument("em-branco.png", "   ");

        assertThat(service.extractPending(CASE_ID))
                .singleElement()
                .satisfies(content -> {
                    assertThat(content.status()).isEqualTo(TextExtractionStatus.NO_TEXT_FOUND);
                    assertThat(content.method()).isEqualTo(TextExtractionMethod.OCR);
                });
    }

    @Test
    @DisplayName("falha de ambiente sobe, mas o que já foi extraído fica gravado")
    void shouldPropagateEnvironmentFailureKeepingPreviousWork() {
        givenDocument("contrato.pdf", "texto");
        givenDocument("scan.png", "imagem");
        extractor.willAnswer((format, content) -> {
            if (format == DocumentFormat.PNG) {
                throw new DocumentTextExtractionException("Tesseract não está instalado");
            }
            return ScriptedTextExtractor.echo(format, content);
        });

        assertThatExceptionOfType(DocumentTextExtractionException.class)
                .isThrownBy(() -> service.extractPending(CASE_ID));
        assertThat(textContentRepository.all()).singleElement()
                .satisfies(content -> assertThat(content.content()).isEqualTo("texto"));
    }

    @Test
    @DisplayName("uma nova tentativa lê só os documentos que ainda não têm texto")
    void shouldResumeWithoutRepeatingExtractedDocuments() {
        Document pdf = givenDocument("contrato.pdf", "texto");
        Document png = givenDocument("scan.png", "imagem");
        extractor.willAnswer((format, content) -> {
            if (format == DocumentFormat.PNG) {
                throw new DocumentTextExtractionException("tempo esgotado");
            }
            return ScriptedTextExtractor.echo(format, content);
        });
        assertThatExceptionOfType(DocumentTextExtractionException.class)
                .isThrownBy(() -> service.extractPending(CASE_ID));

        extractor.willAnswer(ScriptedTextExtractor::echo);
        List<DocumentTextContent> result = service.extractPending(CASE_ID);

        assertThat(result).extracting(DocumentTextContent::documentId).containsExactly(pdf.id(), png.id());
        // PDF, PNG (falhou), PNG de novo — o PDF não é lido uma segunda vez.
        assertThat(extractor.calls()).containsExactly(DocumentFormat.PDF, DocumentFormat.PNG, DocumentFormat.PNG);
        assertThat(service.extractPending(CASE_ID)).hasSize(2);
        assertThat(extractor.calls()).hasSize(3);
    }

    @Test
    @DisplayName("objeto ausente no storage é falha de infraestrutura, não documento ilegível")
    void shouldPropagateStorageFailure() {
        documentRepository.saveAll(List.of(new Document(
                UUID.randomUUID(),
                CASE_ID,
                "sumiu.pdf",
                "legal-cases/inexistente.pdf",
                "application/pdf",
                Sha256Checksum.ofContent(new byte[] {1}),
                NOW)));

        assertThatExceptionOfType(DocumentNotFoundInStorageException.class)
                .isThrownBy(() -> service.extractPending(CASE_ID));
        assertThat(textContentRepository.all()).isEmpty();
    }

    @Test
    @DisplayName("demanda sem documentos não produz nada")
    void shouldReturnEmptyForCaseWithoutDocuments() {
        assertThat(service.extractPending(UUID.randomUUID())).isEmpty();
        assertThat(extractor.calls()).isEmpty();
    }
}
