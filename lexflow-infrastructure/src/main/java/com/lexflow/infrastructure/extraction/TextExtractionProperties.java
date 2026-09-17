package com.lexflow.infrastructure.extraction;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração da extração de texto, lida de {@code lexflow.extraction}.
 *
 * @param ocrLanguage idiomas do Tesseract, no formato dele ({@code por}, {@code por+eng}); o pacote
 *     de cada idioma precisa estar instalado junto com o Tesseract
 * @param tesseractPath diretório do executável {@code tesseract}; vazio significa procurar no
 *     {@code PATH}
 * @param ocrTimeout tempo máximo de OCR por imagem ou por página — a seção 11 exige timeout explícito
 *     em toda integração externa, e o Tesseract é um processo externo
 * @param ocrDpi resolução com que as páginas de um PDF digitalizado são renderizadas antes do OCR;
 *     300 é o ponto de equilíbrio usual entre precisão e tempo
 * @param minNativeCharactersPerPage abaixo desta média de letras e dígitos por página, um PDF é
 *     tratado como digitalizado e vai para o OCR; evita que um número de página ou um carimbo solto
 *     faça um documento escaneado passar por PDF com texto
 */
@ConfigurationProperties(prefix = "lexflow.extraction")
public record TextExtractionProperties(
        String ocrLanguage,
        String tesseractPath,
        Duration ocrTimeout,
        Integer ocrDpi,
        Integer minNativeCharactersPerPage) {

    public TextExtractionProperties {
        ocrLanguage = ocrLanguage == null || ocrLanguage.isBlank() ? "por" : ocrLanguage.trim();
        tesseractPath = tesseractPath == null ? "" : tesseractPath.trim();
        ocrTimeout = ocrTimeout == null ? Duration.ofMinutes(2) : ocrTimeout;
        ocrDpi = ocrDpi == null ? 300 : ocrDpi;
        minNativeCharactersPerPage = minNativeCharactersPerPage == null ? 10 : minNativeCharactersPerPage;
        if (ocrTimeout.isNegative() || ocrTimeout.isZero()) {
            throw new IllegalArgumentException("lexflow.extraction.ocr-timeout deve ser positivo");
        }
        if (ocrDpi < 72) {
            throw new IllegalArgumentException("lexflow.extraction.ocr-dpi deve ser ao menos 72");
        }
        if (minNativeCharactersPerPage < 1) {
            throw new IllegalArgumentException("lexflow.extraction.min-native-characters-per-page deve ser positivo");
        }
    }

    /** Configuração padrão, usada pelos testes e por quem não precisa ajustar nada. */
    public static TextExtractionProperties defaults() {
        return new TextExtractionProperties(null, null, null, null, null);
    }
}
