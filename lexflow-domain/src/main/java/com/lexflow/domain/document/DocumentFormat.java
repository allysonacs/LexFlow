package com.lexflow.domain.document;

import com.lexflow.domain.exception.UnsupportedDocumentFormatException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Formatos de arquivo aceitos na ingestão de uma demanda (Prompt 05).
 *
 * <p>É uma regra de negócio determinística — quais arquivos a área jurídica aceita receber —, por
 * isso fica no domínio e não em uma configuração da camada web: a mesma lista vale para a API REST,
 * para um eventual reenvio por fila e para qualquer outro ponto de entrada.
 *
 * <p>A lista inicial é a mínima pedida pela base de conhecimento (PDF, DOCX, JPG e PNG). Ampliá-la é
 * uma decisão de negócio, não uma conveniência técnica.
 */
public enum DocumentFormat {

    /** Documento PDF: formato mais comum de contratos e petições. */
    PDF(Set.of("pdf"), "application/pdf", Set.of("application/pdf")),

    /** Documento Word no formato Open XML. */
    DOCX(
            Set.of("docx"),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),

    /** Imagem JPEG: digitalização ou foto de um documento. */
    JPEG(Set.of("jpg", "jpeg"), "image/jpeg", Set.of("image/jpeg", "image/jpg")),

    /** Imagem PNG: digitalização ou captura de tela de um documento. */
    PNG(Set.of("png"), "image/png", Set.of("image/png"));

    /**
     * Tipos genéricos que um cliente HTTP envia quando não sabe identificar o arquivo. São tolerados:
     * quem manda na validação é a extensão, não o cabeçalho enviado pelo cliente.
     */
    private static final Set<String> GENERIC_MIME_TYPES =
            Set.of("application/octet-stream", "binary/octet-stream");

    private final Set<String> extensions;
    private final String canonicalMimeType;
    private final Set<String> acceptedMimeTypes;

    DocumentFormat(Set<String> extensions, String canonicalMimeType, Set<String> acceptedMimeTypes) {
        this.extensions = extensions;
        this.canonicalMimeType = canonicalMimeType;
        this.acceptedMimeTypes = acceptedMimeTypes;
    }

    /** Extensões de arquivo que identificam este formato, sem o ponto. */
    public Set<String> extensions() {
        return extensions;
    }

    /** Mime type gravado em {@code documents.mime_type}, independente do que o cliente declarou. */
    public String canonicalMimeType() {
        return canonicalMimeType;
    }

    /**
     * Resolve o formato pela extensão do nome do arquivo.
     *
     * @throws UnsupportedDocumentFormatException se o arquivo não tiver extensão ou ela não estiver
     *     na lista de formatos aceitos
     */
    public static DocumentFormat ofFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new UnsupportedDocumentFormatException("(sem nome)", supportedExtensions());
        }
        String extension = extensionOf(fileName);
        return Arrays.stream(values())
                .filter(format -> format.extensions.contains(extension))
                .findFirst()
                .orElseThrow(() -> new UnsupportedDocumentFormatException(fileName, supportedExtensions()));
    }

    /**
     * Indica se o mime type declarado pelo cliente é compatível com este formato.
     *
     * <p>Um mime type ausente ou genérico é considerado compatível: navegadores e clientes HTTP
     * costumam enviar {@code application/octet-stream} para qualquer anexo, e recusar por causa disso
     * barraria envios legítimos.
     */
    public boolean acceptsMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return true;
        }
        String normalized = normalizeMimeType(mimeType);
        return GENERIC_MIME_TYPES.contains(normalized) || acceptedMimeTypes.contains(normalized);
    }

    /** Todas as extensões aceitas, em ordem alfabética, para compor mensagens de erro. */
    public static Set<String> supportedExtensions() {
        return Arrays.stream(values())
                .flatMap(format -> format.extensions.stream())
                .sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String extensionOf(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT).trim();
    }

    /** Descarta parâmetros como {@code ; charset=UTF-8} e normaliza para minúsculas. */
    private static String normalizeMimeType(String mimeType) {
        int parameterSeparator = mimeType.indexOf(';');
        String bare = parameterSeparator < 0 ? mimeType : mimeType.substring(0, parameterSeparator);
        return bare.trim().toLowerCase(Locale.ROOT);
    }
}
