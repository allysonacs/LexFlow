package com.lexflow.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lexflow.application.document.DocumentTextExtractor;
import com.lexflow.application.document.ExtractedText;
import com.lexflow.application.knowledge.support.KnowledgeBaseTestDoubles;
import com.lexflow.application.knowledge.support.KnowledgeBaseTestDoubles.InMemoryKnowledgeBaseChunkRepository;
import com.lexflow.application.knowledge.support.KnowledgeBaseTestDoubles.InMemoryKnowledgeBaseSourceRepository;
import com.lexflow.application.knowledge.support.KnowledgeBaseTestDoubles.LexicalEmbeddingClient;
import com.lexflow.application.pagination.PageQuery;
import com.lexflow.domain.document.TextExtractionMethod;
import com.lexflow.domain.exception.KnowledgeBaseSourceNotFoundException;
import com.lexflow.domain.knowledge.ChunkingPolicy;
import com.lexflow.domain.knowledge.KnowledgeBaseSourceType;
import com.lexflow.domain.knowledge.TextChunker;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Indexação e recuperação da base normativa, sem banco e sem provedor externo (Prompt 12).
 *
 * <p>O modelo de embeddings é determinístico e lexical: ele reproduz a única propriedade de que estes
 * testes dependem — textos sobre o mesmo assunto ficam próximos — sem depender de rede.
 */
class KnowledgeBaseRagTest {

    private static final String ALCADAS =
            """
            Art. 1º A assinatura de contratos com valor superior a cem mil reais depende de aprovação prévia do diretor jurídico.

            Art. 2º Contratos com valor igual ou inferior a cem mil reais podem ser assinados pelo gerente da área.
            """;

    private static final String PAGAMENTO_DE_ACORDOS =
            """
            Art. 1º O pagamento de acordo judicial depende de homologação pelo juízo competente.

            Art. 2º A quitação de acordo exige comprovante de depósito e termo de quitação assinado pelas partes.
            """;

    private static final String CADASTRO_DE_FORNECEDORES =
            """
            Art. 1º A contratação de fornecedor exige cartão CNPJ ativo e certidões de regularidade fiscal.

            Art. 2º O cadastro do fornecedor é revalidado a cada doze meses.
            """;

    private InMemoryKnowledgeBaseSourceRepository sources;
    private InMemoryKnowledgeBaseChunkRepository chunks;
    private LexicalEmbeddingClient embeddingClient;
    private IngestKnowledgeBaseSourceService ingestionService;

    @BeforeEach
    void setUp() {
        sources = new InMemoryKnowledgeBaseSourceRepository();
        chunks = new InMemoryKnowledgeBaseChunkRepository(sources);
        embeddingClient = new LexicalEmbeddingClient();
        ingestionService = newService((format, content) -> new ExtractedText(
                new String(content, StandardCharsets.UTF_8), TextExtractionMethod.NATIVE_TEXT));
    }

    @Test
    @DisplayName("uma fonte enviada como texto é dividida, vetorizada e gravada com os seus trechos")
    void shouldIndexSourceFromText() {
        KnowledgeBaseIngestionResult result = ingestionService.ingest(new IngestKnowledgeBaseSourceCommand(
                "Política de Alçadas", KnowledgeBaseSourceType.INTERNAL_POLICY, LocalDate.of(2026, 1, 1), ALCADAS));

        assertThat(result.chunkCount()).isPositive();
        assertThat(result.indexedCharacters()).isPositive();
        assertThat(result.source().title()).isEqualTo("Política de Alçadas");
        assertThat(sources.size()).isEqualTo(1);
        assertThat(chunks.size()).isEqualTo(result.chunkCount());
        assertThat(chunks.findBySourceId(result.source().id()))
                .allSatisfy(chunk -> assertThat(chunk.isIndexed()).isTrue());
        // Cada trecho foi vetorizado como documento, não como consulta.
        assertThat(embeddingClient.documentTexts()).hasSize(result.chunkCount());
        assertThat(embeddingClient.queryTexts()).isEmpty();
    }

    @Test
    @DisplayName("critério de aceite: uma consulta relacionada a uma das fontes traz os trechos dela no topo")
    void shouldRetrieveTheRelevantChunksAmongTheTopResults() {
        UUID alcadas = ingestionService
                .ingest(new IngestKnowledgeBaseSourceCommand(
                        "Política de Alçadas", KnowledgeBaseSourceType.INTERNAL_POLICY, null, ALCADAS))
                .source()
                .id();
        ingestionService.ingest(new IngestKnowledgeBaseSourceCommand(
                "Política de Acordos", KnowledgeBaseSourceType.INTERNAL_POLICY, null, PAGAMENTO_DE_ACORDOS));
        ingestionService.ingest(new IngestKnowledgeBaseSourceCommand(
                "Política de Fornecedores", KnowledgeBaseSourceType.INTERNAL_POLICY, null, CADASTRO_DE_FORNECEDORES));

        KnowledgeBaseRetriever retriever = new KnowledgeBaseRetriever(embeddingClient, chunks, 3, 0.0);
        List<RetrievedChunk> retrieved =
                retriever.retrieve("Podemos assinar esse contrato com valor superior a cem mil reais?");

        assertThat(retrieved).hasSize(3);
        assertThat(retrieved.getFirst().sourceId()).isEqualTo(alcadas);
        assertThat(retrieved.getFirst().content()).contains("diretor jurídico");
        assertThat(retrieved.getFirst().citation()).startsWith("Política de Alçadas #");
        // A ordem é do mais próximo ao mais distante.
        assertThat(retrieved.stream().map(RetrievedChunk::similarity).toList())
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(embeddingClient.queryTexts()).hasSize(1);
    }

    @Test
    @DisplayName("trechos abaixo do corte de similaridade são descartados, para não empurrar ruído ao modelo")
    void shouldDropChunksBelowTheMinimumSimilarity() {
        ingestionService.ingest(new IngestKnowledgeBaseSourceCommand(
                "Política de Acordos", KnowledgeBaseSourceType.INTERNAL_POLICY, null, PAGAMENTO_DE_ACORDOS));

        KnowledgeBaseRetriever semCorte = new KnowledgeBaseRetriever(embeddingClient, chunks, 5, 0.0);
        KnowledgeBaseRetriever comCorte = new KnowledgeBaseRetriever(embeddingClient, chunks, 5, 0.99);

        String consulta = "Qual é o prazo de garantia de um equipamento importado?";
        assertThat(semCorte.retrieve(consulta)).isNotEmpty();
        assertThat(comCorte.retrieve(consulta)).isEmpty();
    }

    @Test
    @DisplayName("os trechos citados são devolvidos na ordem em que a IA os citou, ignorando os removidos")
    void shouldReturnCitedChunksInTheOrderTheyWereCited() {
        UUID sourceId = ingestionService
                .ingest(new IngestKnowledgeBaseSourceCommand(
                        "Política de Alçadas", KnowledgeBaseSourceType.INTERNAL_POLICY, null, ALCADAS))
                .source()
                .id();
        List<UUID> ids = chunks.findBySourceId(sourceId).stream()
                .map(com.lexflow.domain.knowledge.KnowledgeBaseChunk::id)
                .toList();
        KnowledgeBaseRetriever retriever = new KnowledgeBaseRetriever(embeddingClient, chunks, 5, 0.0);

        List<UUID> citados = List.of(ids.getLast(), UUID.randomUUID(), ids.getFirst());
        List<RetrievedChunk> encontrados = retriever.findCited(citados);

        assertThat(encontrados.stream().map(RetrievedChunk::chunkId))
                .containsExactly(ids.getLast(), ids.getFirst());
        assertThat(retriever.findCited(List.of())).isEmpty();
        assertThat(retriever.findCited(null)).isEmpty();
    }

    @Test
    @DisplayName("reindexar substitui os trechos antigos, em vez de duplicar a norma na base")
    void shouldReplaceChunksOnReindex() {
        KnowledgeBaseIngestionResult first = ingestionService.ingest(new IngestKnowledgeBaseSourceCommand(
                "Política de Alçadas", KnowledgeBaseSourceType.INTERNAL_POLICY, null, ALCADAS));

        KnowledgeBaseIngestionResult second = ingestionService.reindex(
                first.source().id(), "Art. 1º Toda assinatura depende de aprovação do conselho.");

        assertThat(chunks.size()).isEqualTo(second.chunkCount());
        assertThat(chunks.findBySourceId(first.source().id()))
                .singleElement()
                .satisfies(chunk -> assertThat(chunk.content()).contains("conselho"));
    }

    @Test
    @DisplayName("uma fonte enviada como arquivo passa pelo extrator de documentos")
    void shouldIndexSourceFromFile() {
        IngestKnowledgeBaseSourceService comExtrator = newService(
                (format, content) -> new ExtractedText(ALCADAS, TextExtractionMethod.NATIVE_TEXT));

        KnowledgeBaseIngestionResult result = comExtrator.ingest(new IngestKnowledgeBaseFileCommand(
                "Política de Alçadas",
                KnowledgeBaseSourceType.INTERNAL_POLICY,
                null,
                "alcadas.pdf",
                "application/pdf",
                "conteúdo binário".getBytes(StandardCharsets.UTF_8)));

        assertThat(result.chunkCount()).isPositive();
        assertThat(chunks.findBySourceId(result.source().id()).getFirst().content()).contains("diretor jurídico");
    }

    @Test
    @DisplayName("um arquivo de texto puro é lido sem passar pelo extrator")
    void shouldReadPlainTextFileDirectly() {
        IngestKnowledgeBaseSourceService semExtrator = newService((format, content) -> {
            throw new AssertionError("o extrator não deveria ser chamado para texto puro");
        });

        KnowledgeBaseIngestionResult result = semExtrator.ingest(new IngestKnowledgeBaseFileCommand(
                "Política de Acordos",
                KnowledgeBaseSourceType.INTERNAL_POLICY,
                null,
                "acordos.TXT",
                "text/plain",
                PAGAMENTO_DE_ACORDOS.getBytes(StandardCharsets.UTF_8)));

        assertThat(chunks.findBySourceId(result.source().id()).getFirst().content()).contains("homologação");
    }

    @Test
    @DisplayName("um texto sem conteúdo indexável é recusado, em vez de criar uma fonte que nunca é recuperada")
    void shouldRejectSourceWithoutIndexableText() {
        assertThatThrownBy(() -> ingestionService.ingest(new IngestKnowledgeBaseSourceCommand(
                        "Norma vazia", KnowledgeBaseSourceType.LAW, null, "   ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(sources.size()).isZero();
    }

    @Test
    @DisplayName("uma fonte inexistente é recusada na consulta e na reindexação")
    void shouldRejectUnknownSource() {
        UUID desconhecida = UUID.randomUUID();

        assertThatThrownBy(() -> ingestionService.findById(desconhecida))
                .isInstanceOf(KnowledgeBaseSourceNotFoundException.class);
        assertThatThrownBy(() -> ingestionService.reindex(desconhecida, "texto"))
                .isInstanceOf(KnowledgeBaseSourceNotFoundException.class);
    }

    @Test
    @DisplayName("a listagem de fontes é paginada e ordenada por título")
    void shouldListSourcesPaginated() {
        ingestionService.ingest(new IngestKnowledgeBaseSourceCommand(
                "Política de Fornecedores", KnowledgeBaseSourceType.INTERNAL_POLICY, null, CADASTRO_DE_FORNECEDORES));
        ingestionService.ingest(new IngestKnowledgeBaseSourceCommand(
                "Política de Acordos", KnowledgeBaseSourceType.INTERNAL_POLICY, null, PAGAMENTO_DE_ACORDOS));

        assertThat(ingestionService.list(PageQuery.of(0, 1)).content())
                .singleElement()
                .satisfies(source -> assertThat(source.title()).isEqualTo("Política de Acordos"));
        assertThat(ingestionService.list(PageQuery.of(0, 10)).totalElements()).isEqualTo(2);
        assertThat(ingestionService.findById(ingestionService
                                .list(PageQuery.of(0, 10))
                                .content()
                                .getFirst()
                                .id())
                        .chunks())
                .isNotEmpty();
    }

    @Test
    @DisplayName("uma consulta vazia ou um limite inválido são recusados antes de chamar o provedor")
    void shouldRejectInvalidRetrievalArguments() {
        KnowledgeBaseRetriever retriever = new KnowledgeBaseRetriever(embeddingClient, chunks, 5, 0.0);

        assertThatThrownBy(() -> retriever.retrieve("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retriever.retrieve("consulta", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeBaseRetriever(embeddingClient, chunks, 0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeBaseRetriever(embeddingClient, chunks, 5, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(embeddingClient.queryTexts()).isEmpty();
    }

    private IngestKnowledgeBaseSourceService newService(DocumentTextExtractor textExtractor) {
        return new IngestKnowledgeBaseSourceService(
                sources,
                chunks,
                embeddingClient,
                new TextChunker(ChunkingPolicy.DEFAULT),
                textExtractor,
                KnowledgeBaseTestDoubles.directTransactionRunner(),
                UUID::randomUUID);
    }
}
