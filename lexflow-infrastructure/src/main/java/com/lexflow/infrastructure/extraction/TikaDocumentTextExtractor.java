package com.lexflow.infrastructure.extraction;

import com.lexflow.application.document.DocumentTextExtractor;
import com.lexflow.application.document.ExtractedText;
import com.lexflow.application.exception.DocumentTextExtractionException;
import com.lexflow.application.exception.UnreadableDocumentException;
import com.lexflow.domain.document.DocumentFormat;
import com.lexflow.domain.document.TextExtractionMethod;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.PagedText;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

/**
 * Extrai texto com Apache Tika, recorrendo ao Tesseract para OCR.
 *
 * <p>A estratégia depende do formato:
 *
 * <ul>
 *   <li><strong>PDF</strong>: primeiro lê a camada de texto. Se ela for pobre demais — menos do que
 *       {@link TextExtractionProperties#minNativeCharactersPerPage()} letras e dígitos por página, em
 *       média —, o PDF é tratado como digitalizado: as páginas são renderizadas e passam pelo OCR.
 *   <li><strong>DOCX</strong>: só a camada de texto. Imagens embutidas não passam por OCR.
 *   <li><strong>JPEG e PNG</strong>: OCR direto.
 * </ul>
 *
 * <p>O parser é escolhido pelo formato já validado na ingestão, e não por detecção de conteúdo: a
 * regra de quais arquivos são aceitos é do domínio ({@link DocumentFormat}), e o adapter não deve
 * reinterpretá-la.
 *
 * <p><strong>Tesseract ausente é erro de ambiente, não do documento.</strong> Sem ele, o Tika
 * devolveria texto vazio em silêncio, e um documento digitalizado seria registrado como "sem texto".
 * Aqui a falta do executável vira {@link DocumentTextExtractionException}, que devolve a mensagem para
 * a fila até alguém corrigir a instalação.
 *
 * <p>Nenhum log deste adapter inclui o texto extraído (seção 12).
 */
public class TikaDocumentTextExtractor implements DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TikaDocumentTextExtractor.class);

    /** Sem limite de caracteres: o tamanho já é limitado pelo upload (Prompt 05). */
    private static final int UNLIMITED = -1;

    private final TextExtractionProperties properties;
    private final PDFParser pdfParser = new PDFParser();
    private final OOXMLParser docxParser = new OOXMLParser();
    private final TesseractOCRParser ocrParser;
    private final boolean ocrAvailable;

    public TikaDocumentTextExtractor(TextExtractionProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties não pode ser nulo");
        this.ocrParser = buildOcrParser(properties);
        this.ocrAvailable = detectTesseract(ocrParser);
        if (ocrAvailable) {
            log.info("OCR disponível: Tesseract com idioma '{}'", properties.ocrLanguage());
        } else {
            // Não impede a aplicação de subir: PDFs com texto e DOCX continuam sendo processados.
            log.warn("Tesseract não encontrado (caminho configurado: '{}'). Imagens e PDFs digitalizados "
                    + "vão falhar até que ele seja instalado.", properties.tesseractPath());
        }
    }

    /** Indica se o Tesseract foi encontrado na inicialização. */
    public boolean isOcrAvailable() {
        return ocrAvailable;
    }

    @Override
    public ExtractedText extract(DocumentFormat format, byte[] content) {
        Objects.requireNonNull(format, "format não pode ser nulo");
        Objects.requireNonNull(content, "content não pode ser nulo");
        return switch (format) {
            case PDF -> extractPdf(content);
            case DOCX -> new ExtractedText(
                    parse(docxParser, content, format, nativeTextContext()).text(), TextExtractionMethod.NATIVE_TEXT);
            case JPEG, PNG -> new ExtractedText(ocrImage(content, format), TextExtractionMethod.OCR);
        };
    }

    private ExtractedText extractPdf(byte[] content) {
        ParseResult nativeResult = parse(pdfParser, content, DocumentFormat.PDF, pdfContext(PDFParserConfig.OCR_STRATEGY.NO_OCR));
        if (hasMeaningfulText(nativeResult)) {
            return new ExtractedText(nativeResult.text(), TextExtractionMethod.NATIVE_TEXT);
        }
        requireOcr();
        ParseResult ocrResult = parse(pdfParser, content, DocumentFormat.PDF, pdfContext(PDFParserConfig.OCR_STRATEGY.OCR_ONLY));
        return new ExtractedText(ocrResult.text(), TextExtractionMethod.OCR);
    }

    private String ocrImage(byte[] content, DocumentFormat format) {
        requireOcr();
        return parse(ocrParser, content, format, ocrContext()).text();
    }

    /**
     * Um PDF tem texto próprio quando a média de letras e dígitos por página atinge o mínimo
     * configurado. Espaços e pontuação não contam: um PDF digitalizado costuma trazer só quebras de
     * linha na camada de texto.
     */
    private boolean hasMeaningfulText(ParseResult result) {
        long significant = result.text().codePoints().filter(Character::isLetterOrDigit).count();
        int pages = Math.max(1, result.pageCount());
        return significant >= (long) pages * properties.minNativeCharactersPerPage();
    }

    private ParseResult parse(Parser parser, byte[] content, DocumentFormat format, ParseContext context) {
        BodyContentHandler handler = new BodyContentHandler(UNLIMITED);
        Metadata metadata = new Metadata();
        metadata.set(Metadata.CONTENT_TYPE, format.canonicalMimeType());
        try (TikaInputStream stream = TikaInputStream.get(content)) {
            parser.parse(stream, handler, metadata, context);
        } catch (EncryptedDocumentException e) {
            throw new UnreadableDocumentException("Documento %s protegido por senha".formatted(format), e);
        } catch (TikaException e) {
            if (isTimeout(e)) {
                throw new DocumentTextExtractionException(
                        "OCR excedeu o tempo limite de %s".formatted(properties.ocrTimeout()), e);
            }
            throw new UnreadableDocumentException(
                    "Não foi possível interpretar o documento %s: %s".formatted(format, rootMessage(e)), e);
        } catch (SAXException e) {
            throw new UnreadableDocumentException(
                    "Estrutura inválida no documento %s: %s".formatted(format, rootMessage(e)), e);
        } catch (IOException e) {
            // O conteúdo está em memória: um IOException aqui vem do arquivo malformado ou do processo
            // externo do OCR. Só o segundo caso é problema de ambiente.
            if (isExternalProcessFailure(e)) {
                throw new DocumentTextExtractionException("Falha ao executar o Tesseract: " + rootMessage(e), e);
            }
            throw new UnreadableDocumentException(
                    "Não foi possível ler o documento %s: %s".formatted(format, rootMessage(e)), e);
        }
        Integer pages = metadata.getInt(PagedText.N_PAGES);
        return new ParseResult(handler.toString(), pages == null ? 0 : pages);
    }

    /** Contexto das leituras que não devem acionar OCR. */
    private ParseContext nativeTextContext() {
        ParseContext context = new ParseContext();
        TesseractOCRConfig ocrConfig = new TesseractOCRConfig();
        ocrConfig.setSkipOcr(true);
        context.set(TesseractOCRConfig.class, ocrConfig);
        return context;
    }

    private ParseContext pdfContext(PDFParserConfig.OCR_STRATEGY strategy) {
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(strategy);
        pdfConfig.setOcrDPI(properties.ocrDpi());
        pdfConfig.setExtractInlineImages(false);

        ParseContext context = strategy == PDFParserConfig.OCR_STRATEGY.NO_OCR ? nativeTextContext() : ocrContext();
        context.set(PDFParserConfig.class, pdfConfig);
        // É por aqui que o PDFParser encontra quem faz o OCR das páginas renderizadas.
        context.set(Parser.class, ocrParser);
        return context;
    }

    private ParseContext ocrContext() {
        TesseractOCRConfig ocrConfig = new TesseractOCRConfig();
        ocrConfig.setLanguage(properties.ocrLanguage());
        ocrConfig.setTimeoutSeconds(timeoutSeconds(properties));
        ParseContext context = new ParseContext();
        context.set(TesseractOCRConfig.class, ocrConfig);
        return context;
    }

    private void requireOcr() {
        if (!ocrAvailable) {
            throw new DocumentTextExtractionException(
                    "Tesseract não está instalado ou não foi encontrado; o documento precisa de OCR");
        }
    }

    private static TesseractOCRParser buildOcrParser(TextExtractionProperties properties) {
        TesseractOCRParser parser = new TesseractOCRParser();
        if (!properties.tesseractPath().isEmpty()) {
            parser.setTesseractPath(properties.tesseractPath());
        }
        parser.setLanguage(properties.ocrLanguage());
        parser.setTimeout(timeoutSeconds(properties));
        try {
            parser.initialize(Map.of());
        } catch (TikaConfigException e) {
            throw new IllegalStateException("Configuração inválida do Tesseract: " + e.getMessage(), e);
        }
        return parser;
    }

    private static boolean detectTesseract(TesseractOCRParser parser) {
        try {
            return parser.hasTesseract();
        } catch (TikaConfigException e) {
            log.warn("Não foi possível verificar a instalação do Tesseract: {}", e.getMessage());
            return false;
        }
    }

    private static int timeoutSeconds(TextExtractionProperties properties) {
        return (int) Math.max(1, properties.ocrTimeout().toSeconds());
    }

    private static boolean isTimeout(Throwable error) {
        return messageChainContains(error, "timeout") || messageChainContains(error, "timed out");
    }

    private static boolean isExternalProcessFailure(Throwable error) {
        return messageChainContains(error, "tesseract") || messageChainContains(error, "cannot run program");
    }

    private static boolean messageChainContains(Throwable error, String fragment) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** Mensagem da causa mais interna, que costuma ser a mais informativa. */
    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    /** Texto lido e quantidade de páginas informada pelo parser (zero quando o formato não pagina). */
    private record ParseResult(String text, int pageCount) {}
}
