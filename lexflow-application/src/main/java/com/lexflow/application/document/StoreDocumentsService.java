package com.lexflow.application.document;

import com.lexflow.domain.document.Document;
import com.lexflow.domain.document.DocumentFormat;
import com.lexflow.domain.document.Sha256Checksum;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Envia os binários recebidos ao storage e monta os metadados correspondentes.
 *
 * <p>Serve tanto à ingestão de uma demanda nova (Prompt 05) quanto ao reenvio de documentação de uma
 * demanda devolvida para correção (Prompt 15): o caminho do arquivo até o storage é o mesmo, e tê-lo
 * em um lugar só evita que as duas entradas divirjam com o tempo.
 *
 * <p>O checksum é calculado aqui, sobre o conteúdo recebido, e não delegado ao storage: é ele que
 * sustenta a detecção de reenvio do mesmo arquivo, e essa garantia não pode depender do adapter que
 * estiver em uso.
 */
public class StoreDocumentsService {

    private final DocumentStoragePort documentStorage;
    private final Supplier<UUID> idGenerator;

    public StoreDocumentsService(DocumentStoragePort documentStorage, Supplier<UUID> idGenerator) {
        this.documentStorage = Objects.requireNonNull(documentStorage, "documentStorage não pode ser nulo");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator não pode ser nulo");
    }

    /**
     * Valida os formatos antes de gravar qualquer coisa.
     *
     * @throws com.lexflow.domain.exception.UnsupportedDocumentFormatException se algum arquivo não
     *     for aceito — e aí nada é enviado ao storage
     */
    public List<DocumentFormat> resolveFormats(List<DocumentUpload> uploads) {
        return uploads.stream().map(DocumentUpload::resolveFormat).toList();
    }

    /**
     * Grava os arquivos e devolve os metadados.
     *
     * <p>Arquivos idênticos dentro da mesma requisição viram um documento só: o banco tem índice
     * único em {@code (legal_case_id, checksum_sha256)} e recusaria a segunda linha.
     *
     * @param alreadyAttached checksums já anexados à demanda, para o reenvio não tentar gravá-los de novo
     */
    public List<Document> store(
            UUID legalCaseId,
            List<DocumentUpload> uploads,
            List<DocumentFormat> formats,
            Set<Sha256Checksum> alreadyAttached,
            Instant uploadedAt) {
        List<Document> documents = new ArrayList<>(uploads.size());
        Set<Sha256Checksum> stored = new HashSet<>(alreadyAttached);
        for (int index = 0; index < uploads.size(); index++) {
            DocumentUpload upload = uploads.get(index);
            Sha256Checksum checksum = Sha256Checksum.ofContent(upload.content());
            if (!stored.add(checksum)) {
                continue;
            }
            String storagePath = documentStorage
                    .store(legalCaseId, formats.get(index), checksum, upload)
                    .storagePath();
            documents.add(new Document(
                    idGenerator.get(),
                    legalCaseId,
                    upload.fileName(),
                    storagePath,
                    // Grava o tipo canônico do formato, e não o que o cliente declarou: assim um
                    // "application/octet-stream" genérico não chega ao banco.
                    formats.get(index).canonicalMimeType(),
                    checksum,
                    uploadedAt,
                    upload.documentType()));
        }
        return List.copyOf(documents);
    }

    /** Grava os arquivos de uma demanda nova, que ainda não tem documento nenhum. */
    public List<Document> store(
            UUID legalCaseId, List<DocumentUpload> uploads, List<DocumentFormat> formats, Instant uploadedAt) {
        return store(legalCaseId, uploads, formats, Set.of(), uploadedAt);
    }
}
