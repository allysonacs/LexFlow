package com.lexflow.application.knowledge;

import com.lexflow.application.document.DocumentTextExtractor;
import com.lexflow.application.document.DocumentUpload;
import com.lexflow.application.pagination.PageQuery;
import com.lexflow.application.pagination.PageResult;
import com.lexflow.application.transaction.TransactionRunner;
import com.lexflow.domain.exception.KnowledgeBaseSourceNotFoundException;
import com.lexflow.domain.knowledge.Embedding;
import com.lexflow.domain.knowledge.KnowledgeBaseChunk;
import com.lexflow.domain.knowledge.KnowledgeBaseSource;
import com.lexflow.domain.knowledge.TextChunk;
import com.lexflow.domain.knowledge.TextChunker;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Indexa uma fonte normativa na base de RAG (Prompt 12, itens 1 a 3).
 *
 * <p>O caminho é sempre o mesmo: dividir o texto em trechos ({@link TextChunker}), gerar o embedding
 * de cada trecho e gravar fonte e trechos na mesma transação. Gravar uma fonte sem os seus trechos
 * criaria uma norma que existe no catálogo mas nunca é recuperada — pior do que não tê-la.
 *
 * <p><strong>Os embeddings são gerados fora da transação.</strong> São dezenas de chamadas de rede;
 * mantê-las dentro prenderia uma conexão do banco durante todo o tempo do provedor, pelo mesmo motivo
 * que o OCR roda fora de transação (seção 11).
 *
 * <p><strong>Reindexar é substituir.</strong> Ao reindexar uma fonte, os trechos antigos são apagados
 * antes dos novos entrarem: manter os dois conjuntos faria a mesma norma ser recuperada em duplicata.
 * Citações antigas que apontem para trechos removidos continuam legíveis, porque a resposta guarda o
 * texto que o revisor precisa ver no momento da revisão.
 */
public class IngestKnowledgeBaseSourceService {

    /** Extensões lidas diretamente como texto, sem passar pelo extrator de documentos. */
    private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of("txt", "md");

    /** O PostgreSQL recusa este caractere em colunas de texto, e PDFs mal gerados o trazem. */
    private static final String NULL_CHARACTER = String.valueOf((char) 0);

    private final KnowledgeBaseSourceRepository sourceRepository;
    private final KnowledgeBaseChunkRepository chunkRepository;
    private final EmbeddingClientPort embeddingClient;
    private final TextChunker chunker;
    private final DocumentTextExtractor textExtractor;
    private final TransactionRunner transactionRunner;
    private final Supplier<UUID> idGenerator;

    public IngestKnowledgeBaseSourceService(
            KnowledgeBaseSourceRepository sourceRepository,
            KnowledgeBaseChunkRepository chunkRepository,
            EmbeddingClientPort embeddingClient,
            TextChunker chunker,
            DocumentTextExtractor textExtractor,
            TransactionRunner transactionRunner,
            Supplier<UUID> idGenerator) {
        this.sourceRepository = Objects.requireNonNull(sourceRepository, "sourceRepository não pode ser nulo");
        this.chunkRepository = Objects.requireNonNull(chunkRepository, "chunkRepository não pode ser nulo");
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "embeddingClient não pode ser nulo");
        this.chunker = Objects.requireNonNull(chunker, "chunker não pode ser nulo");
        this.textExtractor = Objects.requireNonNull(textExtractor, "textExtractor não pode ser nulo");
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner não pode ser nulo");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator não pode ser nulo");
    }

    /**
     * Indexa uma nova fonte normativa.
     *
     * @throws IllegalArgumentException se o texto não produzir nenhum trecho
     * @throws EmbeddingUnavailableException se o provedor de embeddings estiver indisponível
     */
    public KnowledgeBaseIngestionResult ingest(IngestKnowledgeBaseSourceCommand command) {
        Objects.requireNonNull(command, "command não pode ser nulo");
        KnowledgeBaseSource source = new KnowledgeBaseSource(
                idGenerator.get(), command.title(), command.sourceType(), command.effectiveDate());
        return index(source, command.text(), false);
    }

    /**
     * Indexa uma fonte normativa enviada como arquivo.
     *
     * <p>Arquivos {@code .txt} e {@code .md} são lidos como texto; os demais formatos passam pelo
     * mesmo extrator dos documentos das demandas, OCR incluído.
     *
     * @throws com.lexflow.domain.exception.UnsupportedDocumentFormatException se a extensão não for aceita
     */
    public KnowledgeBaseIngestionResult ingest(IngestKnowledgeBaseFileCommand command) {
        Objects.requireNonNull(command, "command não pode ser nulo");
        return ingest(new IngestKnowledgeBaseSourceCommand(
                command.title(), command.sourceType(), command.effectiveDate(), textOf(command)));
    }

    /**
     * Regera os trechos de uma fonte existente, a partir de um novo texto.
     *
     * <p>Serve para quando a norma é republicada ou quando a política de divisão muda: o que estava
     * indexado é substituído por inteiro.
     *
     * @throws KnowledgeBaseSourceNotFoundException se a fonte não existir
     */
    public KnowledgeBaseIngestionResult reindex(UUID sourceId, String text) {
        Objects.requireNonNull(sourceId, "sourceId não pode ser nulo");
        KnowledgeBaseSource source =
                sourceRepository.findById(sourceId).orElseThrow(() -> new KnowledgeBaseSourceNotFoundException(sourceId));
        return index(source, text, true);
    }

    /** Fontes cadastradas, paginadas. */
    public PageResult<KnowledgeBaseSource> list(PageQuery pageQuery) {
        return sourceRepository.findAll(pageQuery);
    }

    /**
     * Uma fonte e os seus trechos, na ordem do documento.
     *
     * @throws KnowledgeBaseSourceNotFoundException se a fonte não existir
     */
    public KnowledgeBaseSourceDetail findById(UUID sourceId) {
        KnowledgeBaseSource source =
                sourceRepository.findById(sourceId).orElseThrow(() -> new KnowledgeBaseSourceNotFoundException(sourceId));
        return new KnowledgeBaseSourceDetail(source, chunkRepository.findBySourceId(sourceId));
    }

    /** Texto do arquivo: leitura direta para texto puro, extrator (com OCR) para os demais formatos. */
    private String textOf(IngestKnowledgeBaseFileCommand command) {
        String extension = extensionOf(command.fileName());
        String text = PLAIN_TEXT_EXTENSIONS.contains(extension)
                ? new String(command.content(), StandardCharsets.UTF_8)
                : textExtractor
                        .extract(
                                new DocumentUpload(command.fileName(), command.declaredMimeType(), command.content())
                                        .resolveFormat(),
                                command.content())
                        .text();
        return text.replace(NULL_CHARACTER, "").strip();
    }

    private static String extensionOf(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot < 0 ? "" : fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT).trim();
    }

    private KnowledgeBaseIngestionResult index(KnowledgeBaseSource source, String text, boolean replaceExisting) {
        List<TextChunk> pieces = chunker.split(text);
        if (pieces.isEmpty()) {
            throw new IllegalArgumentException(
                    "a fonte normativa '%s' não produziu nenhum trecho indexável".formatted(source.title()));
        }

        // Fora da transação: são chamadas de rede, uma por lote de trechos.
        List<Embedding> embeddings = embeddingClient.embedDocuments(pieces.stream().map(TextChunk::content).toList());
        if (embeddings.size() != pieces.size()) {
            throw new EmbeddingUnavailableException(
                    "O provedor de embeddings devolveu %d vetores para %d trechos"
                            .formatted(embeddings.size(), pieces.size()));
        }

        List<KnowledgeBaseChunk> chunks = new ArrayList<>(pieces.size());
        int indexedCharacters = 0;
        for (int i = 0; i < pieces.size(); i++) {
            TextChunk piece = pieces.get(i);
            chunks.add(new KnowledgeBaseChunk(
                    idGenerator.get(), source.id(), piece.index(), piece.content(), embeddings.get(i)));
            indexedCharacters += piece.content().length();
        }

        transactionRunner.runInTransaction(() -> {
            sourceRepository.save(source);
            if (replaceExisting) {
                chunkRepository.deleteBySourceId(source.id());
            }
            chunkRepository.saveAll(chunks);
        });

        return new KnowledgeBaseIngestionResult(source, chunks.size(), indexedCharacters);
    }
}
