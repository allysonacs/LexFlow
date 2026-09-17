package com.lexflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.lexflow.application.document.DocumentTextExtractor;
import com.lexflow.application.knowledge.IngestKnowledgeBaseSourceCommand;
import com.lexflow.application.knowledge.IngestKnowledgeBaseSourceService;
import com.lexflow.application.knowledge.KnowledgeBaseChunkRepository;
import com.lexflow.application.knowledge.KnowledgeBaseIngestionResult;
import com.lexflow.application.knowledge.KnowledgeBaseRetriever;
import com.lexflow.application.knowledge.KnowledgeBaseSourceRepository;
import com.lexflow.application.knowledge.RetrievedChunk;
import com.lexflow.application.pagination.PageQuery;
import com.lexflow.application.transaction.TransactionRunner;
import com.lexflow.domain.knowledge.ChunkingPolicy;
import com.lexflow.domain.knowledge.KnowledgeBaseChunk;
import com.lexflow.domain.knowledge.KnowledgeBaseSourceType;
import com.lexflow.domain.knowledge.TextChunker;
import com.lexflow.infrastructure.persistence.adapter.KnowledgeBaseChunkRepositoryAdapter;
import com.lexflow.infrastructure.persistence.adapter.ProcessingEventIdempotencyAdapter;
import com.lexflow.infrastructure.persistence.adapter.KnowledgeBaseSourceRepositoryAdapter;
import com.lexflow.infrastructure.persistence.repository.KnowledgeBaseChunkJpaRepository;
import com.lexflow.infrastructure.persistence.repository.KnowledgeBaseSourceJpaRepository;
import com.lexflow.infrastructure.persistence.repository.ProcessingEventJpaRepository;
import com.lexflow.infrastructure.testsupport.LexicalEmbeddingClient;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Critério de aceite do Prompt 12: com três fontes normativas indexadas, uma consulta relacionada a
 * uma delas traz os trechos corretos entre os três primeiros resultados.
 *
 * <p>Roda contra o PostgreSQL real com pgvector, porque é o banco que percorre o índice HNSW e calcula
 * a distância de cosseno — uma simulação em memória não provaria que a consulta nativa e o índice
 * funcionam.
 *
 * <p>O modelo de embeddings é o {@link LexicalEmbeddingClient}: determinístico e sem rede.
 */
class KnowledgeBaseRetrievalIT extends AbstractPersistenceIT {

    private static final String ALCADAS =
            """
            Art. 1º A assinatura de contratos com valor superior a cem mil reais depende de aprovação prévia do diretor jurídico.

            Art. 2º Contratos de valor igual ou inferior a cem mil reais podem ser assinados pelo gerente responsável pela área demandante.
            """;

    private static final String ACORDOS =
            """
            Art. 1º O pagamento de acordo judicial depende de homologação pelo juízo competente.

            Art. 2º A quitação do acordo exige comprovante de depósito e termo de quitação assinado por todas as partes.
            """;

    private static final String FORNECEDORES =
            """
            Art. 1º A contratação de fornecedor exige cartão CNPJ ativo e certidões de regularidade fiscal válidas.

            Art. 2º O cadastro do fornecedor é revalidado a cada doze meses, sob pena de suspensão.
            """;

    @Autowired
    private KnowledgeBaseSourceJpaRepository sourceJpaRepository;

    @Autowired
    private KnowledgeBaseChunkJpaRepository chunkJpaRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ProcessingEventJpaRepository processingEventRepository;

    /** Relógio fixo: nenhum teste aqui depende do horário, e a chave de idempotência não é usada. */
    private static final Instant NOW_CLOCK = Instant.parse("2026-03-10T12:00:00Z");

    private KnowledgeBaseSourceRepository sourceRepository;
    private KnowledgeBaseChunkRepository chunkRepository;
    private IngestKnowledgeBaseSourceService ingestionService;
    private KnowledgeBaseRetriever retriever;

    @BeforeEach
    void setUp() {
        // O contexto do @DataJpaTest não carrega os adapters, que são @Component: eles são montados
        // aqui, sobre os repositórios JPA reais.
        sourceRepository = new KnowledgeBaseSourceRepositoryAdapter(sourceJpaRepository);
        chunkRepository = new KnowledgeBaseChunkRepositoryAdapter(chunkJpaRepository);
        LexicalEmbeddingClient embeddingClient = new LexicalEmbeddingClient();
        ingestionService = new IngestKnowledgeBaseSourceService(
                sourceRepository,
                chunkRepository,
                embeddingClient,
                new TextChunker(ChunkingPolicy.DEFAULT),
                unusedExtractor(),
                new ProcessingEventIdempotencyAdapter(processingEventRepository, Clock.fixed(NOW_CLOCK, java.time.ZoneOffset.UTC)),
                directTransactionRunner(),
                UUID::randomUUID);
        retriever = new KnowledgeBaseRetriever(embeddingClient, chunkRepository, 3, 0.0);
    }

    @Test
    @DisplayName("com três fontes indexadas, a consulta traz os trechos da fonte certa no topo")
    void shouldRetrieveTheRightSourceAmongTheTopResults() {
        UUID alcadas = ingest("Política de Alçadas de Assinatura", ALCADAS);
        UUID acordos = ingest("Política de Pagamento de Acordos", ACORDOS);
        UUID fornecedores = ingest("Política de Cadastro de Fornecedores", FORNECEDORES);
        entityManager.flush();

        List<RetrievedChunk> sobreContrato =
                retriever.retrieve("Podemos assinar esse contrato de valor superior a cem mil reais?");
        List<RetrievedChunk> sobreAcordo =
                retriever.retrieve("Podemos pagar esse acordo judicial já homologado pelo juízo?");
        List<RetrievedChunk> sobreFornecedor =
                retriever.retrieve("Podemos contratar esse fornecedor com cartão CNPJ e certidões?");

        assertThat(sobreContrato).hasSize(3);
        assertThat(sobreContrato.getFirst().sourceId()).isEqualTo(alcadas);
        assertThat(sobreContrato.getFirst().content()).contains("diretor jurídico");
        assertThat(sobreAcordo.getFirst().sourceId()).isEqualTo(acordos);
        assertThat(sobreFornecedor.getFirst().sourceId()).isEqualTo(fornecedores);
    }

    @Test
    @DisplayName("os resultados vêm ordenados do mais próximo ao mais distante, com a fonte identificada")
    void shouldOrderResultsBySimilarityAndCarryTheSource() {
        ingest("Política de Alçadas de Assinatura", ALCADAS);
        ingest("Política de Pagamento de Acordos", ACORDOS);
        entityManager.flush();

        List<RetrievedChunk> resultados = retriever.retrieve("aprovação do diretor jurídico para assinar contrato");

        assertThat(resultados).isNotEmpty();
        assertThat(resultados.stream().map(RetrievedChunk::similarity).toList())
                .isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(resultados.getFirst().similarity()).isBetween(-1.0, 1.0);
        assertThat(resultados.getFirst().sourceTitle()).isEqualTo("Política de Alçadas de Assinatura");
        assertThat(resultados.getFirst().sourceType()).isEqualTo(KnowledgeBaseSourceType.INTERNAL_POLICY);
        assertThat(resultados.getFirst().citation()).startsWith("Política de Alçadas de Assinatura #");
    }

    @Test
    @DisplayName("os trechos citados são recuperados pelo identificador, com o texto da fonte")
    void shouldFindCitedChunksById() {
        UUID sourceId = ingest("Política de Alçadas de Assinatura", ALCADAS);
        entityManager.flush();
        List<UUID> ids = chunkRepository.findBySourceId(sourceId).stream()
                .map(KnowledgeBaseChunk::id)
                .toList();

        List<RetrievedChunk> citados = retriever.findCited(List.of(ids.getFirst(), UUID.randomUUID()));

        assertThat(citados).singleElement().satisfies(chunk -> {
            assertThat(chunk.chunkId()).isEqualTo(ids.getFirst());
            assertThat(chunk.content()).contains("Art. 1º");
            assertThat(chunk.sourceTitle()).isEqualTo("Política de Alçadas de Assinatura");
        });
    }

    @Test
    @DisplayName("reindexar substitui os trechos da fonte, sem duplicar a norma na base")
    void shouldReplaceChunksOnReindex() {
        UUID sourceId = ingest("Política de Alçadas de Assinatura", ALCADAS);
        entityManager.flush();
        long antes = chunkRepository.countBySourceId(sourceId);

        KnowledgeBaseIngestionResult result =
                ingestionService.reindex(sourceId, "Art. 1º Toda assinatura depende de aprovação do conselho.");
        entityManager.flush();

        assertThat(antes).isPositive();
        assertThat(chunkRepository.countBySourceId(sourceId)).isEqualTo(result.chunkCount());
        assertThat(chunkRepository.findBySourceId(sourceId))
                .singleElement()
                .satisfies(chunk -> assertThat(chunk.content()).contains("conselho"));
    }

    @Test
    @DisplayName("o embedding gravado é o mesmo que volta do banco, e a fonte aparece na listagem")
    void shouldPersistEmbeddingAndListSources() {
        UUID sourceId = ingest("Política de Alçadas de Assinatura", ALCADAS);
        entityManager.flush();
        entityManager.clear();

        assertThat(chunkRepository.findBySourceId(sourceId))
                .isNotEmpty()
                .allSatisfy(chunk -> assertThat(chunk.embedding().dimension())
                        .isEqualTo(LexicalEmbeddingClient.DIMENSION));
        assertThat(sourceRepository.findAll(PageQuery.of(0, 10)).content())
                .extracting(source -> source.title())
                .contains("Política de Alçadas de Assinatura");
        assertThat(ingestionService.findById(sourceId).source().effectiveDate())
                .isEqualTo(LocalDate.of(2026, 1, 1));
    }

    private UUID ingest(String title, String text) {
        return ingestionService
                .ingest(new IngestKnowledgeBaseSourceCommand(
                        title, KnowledgeBaseSourceType.INTERNAL_POLICY, LocalDate.of(2026, 1, 1), text))
                .source()
                .id();
    }

    /** A transação já é a do teste; o runner apenas executa a ação. */
    private static TransactionRunner directTransactionRunner() {
        return new TransactionRunner() {
            @Override
            public <T> T inTransaction(Supplier<T> action) {
                return action.get();
            }

            @Override
            public void runInTransaction(Runnable action) {
                action.run();
            }
        };
    }

    /** Este teste só indexa texto: o extrator de arquivos não é exercitado aqui. */
    private static DocumentTextExtractor unusedExtractor() {
        return (format, content) -> {
            throw new UnsupportedOperationException("extrator não usado neste teste");
        };
    }
}
