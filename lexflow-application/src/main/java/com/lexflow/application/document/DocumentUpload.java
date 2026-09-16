package com.lexflow.application.document;

import com.lexflow.domain.document.DocumentFormat;
import com.lexflow.domain.exception.UnsupportedDocumentFormatException;
import java.util.Objects;

/**
 * Arquivo recebido em uma requisição de ingestão, já desacoplado do protocolo de transporte.
 *
 * <p>É de propósito uma estrutura simples: a camada de aplicação não conhece {@code MultipartFile}
 * nem nenhum outro tipo do Spring, de modo que o mesmo caso de uso serve a uma API REST, a um
 * consumo de fila ou a uma carga em lote.
 *
 * <p>O conteúdo trafega em memória porque a ingestão trata arquivos pequenos (petições e contratos).
 * Se surgirem anexos grandes, este é o ponto a ser trocado por um fluxo em streaming — a troca fica
 * contida aqui e na porta {@link DocumentStoragePort}.
 *
 * @param declaredMimeType tipo declarado pelo cliente; pode ser nulo ou genérico, já que a validação
 *     de formato se apoia na extensão do arquivo
 */
public record DocumentUpload(String fileName, String declaredMimeType, byte[] content) {

    public DocumentUpload {
        Objects.requireNonNull(content, "content não pode ser nulo");
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName é obrigatório");
        }
        if (content.length == 0) {
            throw new IllegalArgumentException("arquivo '%s' está vazio".formatted(fileName));
        }
    }

    /**
     * Resolve e valida o formato do arquivo.
     *
     * @throws UnsupportedDocumentFormatException se a extensão não for aceita, ou se o tipo
     *     declarado contradisser a extensão
     */
    public DocumentFormat resolveFormat() {
        DocumentFormat format = DocumentFormat.ofFileName(fileName);
        if (!format.acceptsMimeType(declaredMimeType)) {
            throw new UnsupportedDocumentFormatException(fileName, DocumentFormat.supportedExtensions());
        }
        return format;
    }

    /** Tamanho do arquivo em bytes. */
    public int sizeInBytes() {
        return content.length;
    }
}
