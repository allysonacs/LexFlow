package com.lexflow.api.legalcase;

import com.lexflow.domain.document.Document;
import java.time.Instant;
import java.util.UUID;

/**
 * Metadados de um arquivo anexado, como a API os apresenta.
 *
 * <p>Não expõe o {@code storagePath}: o caminho no storage é detalhe interno e o download passará a
 * ser feito por um endereço próprio, no Prompt 06. O checksum é devolvido para que o cliente possa
 * conferir que o arquivo recebido é o que ele enviou.
 */
public record DocumentSummaryResponse(
        UUID id, String fileName, String mimeType, String checksumSha256, Instant uploadedAt) {

    public static DocumentSummaryResponse from(Document document) {
        return new DocumentSummaryResponse(
                document.id(),
                document.fileName(),
                document.mimeType(),
                document.checksum().value(),
                document.uploadedAt());
    }
}
