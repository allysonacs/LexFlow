package com.lexflow.api.config;

import com.lexflow.application.document.DocumentTextExtractor;
import com.lexflow.application.idempotency.IdempotentOperationStore;
import com.lexflow.application.knowledge.EmbeddingClientPort;
import com.lexflow.application.knowledge.IngestKnowledgeBaseSourceService;
import com.lexflow.application.knowledge.KnowledgeBaseChunkRepository;
import com.lexflow.application.knowledge.KnowledgeBaseRetriever;
import com.lexflow.application.knowledge.KnowledgeBaseSourceRepository;
import com.lexflow.application.transaction.TransactionRunner;
import com.lexflow.domain.knowledge.ChunkingPolicy;
import com.lexflow.domain.knowledge.TextChunker;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Montagem da base normativa e da recuperação por similaridade (Prompt 12).
 *
 * <p>Segue o mesmo princípio da {@link LegalCaseUseCaseConfiguration}: as classes de
 * {@code lexflow-application} não têm anotação do Spring, e é aqui que elas são instanciadas.
 */
@Configuration(proxyBeanMethods = false)
public class KnowledgeBaseConfiguration {

    /**
     * Política de divisão das fontes normativas.
     *
     * <p>Mudar estes valores muda os trechos gerados: as fontes já indexadas continuam com a divisão
     * antiga até serem reindexadas.
     */
    @Bean
    public TextChunker knowledgeBaseTextChunker(
            @Value("${lexflow.knowledge-base.chunk-max-characters:1200}") int maxCharacters,
            @Value("${lexflow.knowledge-base.chunk-overlap-characters:200}") int overlapCharacters) {
        return new TextChunker(new ChunkingPolicy(maxCharacters, overlapCharacters));
    }

    @Bean
    public IngestKnowledgeBaseSourceService ingestKnowledgeBaseSourceService(
            KnowledgeBaseSourceRepository sourceRepository,
            KnowledgeBaseChunkRepository chunkRepository,
            EmbeddingClientPort embeddingClient,
            TextChunker knowledgeBaseTextChunker,
            DocumentTextExtractor textExtractor,
            IdempotentOperationStore idempotencyStore,
            TransactionRunner transactionRunner) {
        return new IngestKnowledgeBaseSourceService(
                sourceRepository,
                chunkRepository,
                embeddingClient,
                knowledgeBaseTextChunker,
                textExtractor,
                idempotencyStore,
                transactionRunner,
                UUID::randomUUID);
    }

    /**
     * Recuperação normativa usada pela cadeia de prompts (Prompt 13).
     *
     * @param retrievalLimit quantos trechos são levados ao modelo por pergunta; mais trechos dão mais
     *     contexto, mas também mais chance de ruído
     * @param minSimilarity abaixo deste valor o trecho é descartado, para não empurrar norma
     *     irrelevante ao modelo; zero desliga o corte
     */
    @Bean
    public KnowledgeBaseRetriever knowledgeBaseRetriever(
            EmbeddingClientPort embeddingClient,
            KnowledgeBaseChunkRepository chunkRepository,
            @Value("${lexflow.knowledge-base.retrieval-limit:5}") int retrievalLimit,
            @Value("${lexflow.knowledge-base.min-similarity:0.2}") double minSimilarity) {
        return new KnowledgeBaseRetriever(embeddingClient, chunkRepository, retrievalLimit, minSimilarity);
    }
}
