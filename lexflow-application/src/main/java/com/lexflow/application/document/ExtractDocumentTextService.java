package com.lexflow.application.document;

import com.lexflow.application.exception.UnreadableDocumentException;
import com.lexflow.domain.document.Document;
import com.lexflow.domain.document.DocumentFormat;
import com.lexflow.domain.document.DocumentTextContent;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Caso de uso que extrai o texto de todos os documentos de uma demanda (Prompt 08).
 *
 * <p>É retomável: documentos que já têm texto registrado são pulados. Se o processo cair no meio de
 * uma demanda com vários arquivos — o OCR é lento —, a próxima tentativa continua de onde parou, em
 * vez de repetir tudo.
 *
 * <p>Cada documento é gravado assim que termina, e não todos no fim: o OCR pode levar minutos, e
 * segurar uma transação aberta durante esse tempo prenderia uma conexão do banco sem necessidade.
 *
 * <p>Falhas de storage e de ambiente sobem sem tratamento, para que a mensagem volte para a fila. Só
 * um arquivo ilegível é absorvido aqui, e vira um registro {@code FAILED}.
 */
public class ExtractDocumentTextService {

    private final DocumentRepository documentRepository;
    private final DocumentTextContentRepository textContentRepository;
    private final DocumentStoragePort documentStorage;
    private final DocumentTextExtractor textExtractor;
    private final Clock clock;
    private final Supplier<UUID> idGenerator;

    public ExtractDocumentTextService(
            DocumentRepository documentRepository,
            DocumentTextContentRepository textContentRepository,
            DocumentStoragePort documentStorage,
            DocumentTextExtractor textExtractor,
            Clock clock,
            Supplier<UUID> idGenerator) {
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository não pode ser nulo");
        this.textContentRepository =
                Objects.requireNonNull(textContentRepository, "textContentRepository não pode ser nulo");
        this.documentStorage = Objects.requireNonNull(documentStorage, "documentStorage não pode ser nulo");
        this.textExtractor = Objects.requireNonNull(textExtractor, "textExtractor não pode ser nulo");
        this.clock = Objects.requireNonNull(clock, "clock não pode ser nulo");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator não pode ser nulo");
    }

    /**
     * Extrai o texto dos documentos da demanda que ainda não foram lidos.
     *
     * @return o texto de todos os documentos da demanda, inclusive os extraídos em tentativas
     *     anteriores, na ordem dos documentos
     * @throws com.lexflow.application.exception.DocumentStorageException se o storage falhar
     * @throws com.lexflow.application.exception.DocumentTextExtractionException se a extração
     *     falhar por um problema de ambiente
     */
    public List<DocumentTextContent> extractPending(UUID legalCaseId) {
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");

        Map<UUID, DocumentTextContent> alreadyExtracted = textContentRepository.findByLegalCaseId(legalCaseId).stream()
                .collect(Collectors.toMap(DocumentTextContent::documentId, Function.identity()));

        List<DocumentTextContent> result = new ArrayList<>();
        for (Document document : documentRepository.findByLegalCaseId(legalCaseId)) {
            DocumentTextContent textContent = alreadyExtracted.get(document.id());
            if (textContent == null) {
                textContent = extract(document);
                textContentRepository.save(textContent);
            }
            result.add(textContent);
        }
        return List.copyOf(result);
    }

    private DocumentTextContent extract(Document document) {
        byte[] content = documentStorage.retrieve(document.storagePath());
        try {
            // O formato foi validado na ingestão; resolvê-lo de novo pelo nome mantém uma fonte única
            // para a regra, em vez de confiar no mime type gravado.
            DocumentFormat format = DocumentFormat.ofFileName(document.fileName());
            ExtractedText extracted = textExtractor.extract(format, content);
            return DocumentTextContent.extracted(
                    idGenerator.get(), document, extracted.text(), extracted.method(), clock.instant());
        } catch (UnreadableDocumentException e) {
            return DocumentTextContent.failed(idGenerator.get(), document, e.getMessage(), clock.instant());
        }
    }
}
