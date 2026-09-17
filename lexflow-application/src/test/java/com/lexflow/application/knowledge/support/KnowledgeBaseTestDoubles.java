package com.lexflow.application.knowledge.support;

import com.lexflow.application.knowledge.EmbeddingClientPort;
import com.lexflow.application.knowledge.KnowledgeBaseChunkRepository;
import com.lexflow.application.knowledge.KnowledgeBaseSourceRepository;
import com.lexflow.application.knowledge.RetrievedChunk;
import com.lexflow.application.pagination.PageQuery;
import com.lexflow.application.pagination.PageResult;
import com.lexflow.application.transaction.TransactionRunner;
import com.lexflow.domain.knowledge.Embedding;
import com.lexflow.domain.knowledge.KnowledgeBaseChunk;
import com.lexflow.domain.knowledge.KnowledgeBaseSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Dublês das portas da base normativa. */
public final class KnowledgeBaseTestDoubles {

    private KnowledgeBaseTestDoubles() {
        // classe utilitária
    }

    /** Transação que apenas executa a ação, como nos demais testes de aplicação. */
    public static TransactionRunner directTransactionRunner() {
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

    /**
     * Modelo de embeddings determinístico: cada palavra do texto vira uma dimensão do vetor.
     *
     * <p>Não imita um modelo de verdade — não capta sinônimo nem paráfrase —, mas reproduz a única
     * propriedade de que os testes dependem: textos que falam das mesmas coisas ficam próximos, e
     * textos sobre assuntos diferentes ficam distantes. E, sendo determinístico, o resultado do teste
     * não depende de rede nem de chave de API.
     */
    public static class LexicalEmbeddingClient implements EmbeddingClientPort {

        private final int dimension;
        private final List<String> documentTexts = new ArrayList<>();
        private final List<String> queryTexts = new ArrayList<>();

        public LexicalEmbeddingClient(int dimension) {
            this.dimension = dimension;
        }

        public LexicalEmbeddingClient() {
            this(64);
        }

        @Override
        public int dimension() {
            return dimension;
        }

        @Override
        public Embedding embedDocument(String text) {
            documentTexts.add(text);
            return embed(text);
        }

        @Override
        public List<Embedding> embedDocuments(List<String> texts) {
            documentTexts.addAll(texts);
            return texts.stream().map(this::embed).toList();
        }

        @Override
        public Embedding embedQuery(String text) {
            queryTexts.add(text);
            return embed(text);
        }

        public List<String> documentTexts() {
            return List.copyOf(documentTexts);
        }

        public List<String> queryTexts() {
            return List.copyOf(queryTexts);
        }

        private Embedding embed(String text) {
            float[] values = new float[dimension];
            for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                if (token.length() < 3) {
                    continue;
                }
                values[Math.floorMod(token.hashCode(), dimension)] += 1.0f;
            }
            // Um vetor todo zerado (texto sem palavras) não é aceito pelo domínio.
            values[0] += 0.001f;
            return Embedding.of(values);
        }
    }

    /** Fontes normativas em memória. */
    public static class InMemoryKnowledgeBaseSourceRepository implements KnowledgeBaseSourceRepository {

        private final Map<UUID, KnowledgeBaseSource> sources = new LinkedHashMap<>();

        @Override
        public KnowledgeBaseSource save(KnowledgeBaseSource source) {
            sources.put(source.id(), source);
            return source;
        }

        @Override
        public Optional<KnowledgeBaseSource> findById(UUID id) {
            return Optional.ofNullable(sources.get(id));
        }

        @Override
        public List<KnowledgeBaseSource> findAllById(Collection<UUID> ids) {
            return ids.stream().map(sources::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public PageResult<KnowledgeBaseSource> findAll(PageQuery pageQuery) {
            List<KnowledgeBaseSource> ordered = sources.values().stream()
                    .sorted(Comparator.comparing(KnowledgeBaseSource::title))
                    .toList();
            int from = Math.min(pageQuery.page() * pageQuery.size(), ordered.size());
            int to = Math.min(from + pageQuery.size(), ordered.size());
            return new PageResult<>(ordered.subList(from, to), pageQuery.page(), pageQuery.size(), ordered.size());
        }

        public int size() {
            return sources.size();
        }
    }

    /**
     * Trechos em memória, com a busca por similaridade calculada pelo próprio domínio.
     *
     * <p>É a mesma conta que o PostgreSQL faz com o operador de cosseno do pgvector; aqui ela roda em
     * memória, o que permite testar a recuperação sem Docker.
     */
    public static class InMemoryKnowledgeBaseChunkRepository implements KnowledgeBaseChunkRepository {

        private final Map<UUID, KnowledgeBaseChunk> chunks = new LinkedHashMap<>();
        private final InMemoryKnowledgeBaseSourceRepository sources;

        public InMemoryKnowledgeBaseChunkRepository(InMemoryKnowledgeBaseSourceRepository sources) {
            this.sources = sources;
        }

        @Override
        public void saveAll(List<KnowledgeBaseChunk> newChunks) {
            newChunks.forEach(chunk -> chunks.put(chunk.id(), chunk));
        }

        @Override
        public void deleteBySourceId(UUID sourceId) {
            chunks.values().removeIf(chunk -> chunk.sourceId().equals(sourceId));
        }

        @Override
        public long countBySourceId(UUID sourceId) {
            return chunks.values().stream().filter(chunk -> chunk.sourceId().equals(sourceId)).count();
        }

        @Override
        public List<KnowledgeBaseChunk> findBySourceId(UUID sourceId) {
            return chunks.values().stream()
                    .filter(chunk -> chunk.sourceId().equals(sourceId))
                    .sorted(Comparator.comparingInt(KnowledgeBaseChunk::chunkIndex))
                    .toList();
        }

        @Override
        public List<RetrievedChunk> findAllById(Collection<UUID> chunkIds) {
            return chunkIds.stream()
                    .map(chunks::get)
                    .filter(java.util.Objects::nonNull)
                    .map(chunk -> toRetrieved(chunk, 0.0))
                    .toList();
        }

        @Override
        public List<RetrievedChunk> findMostSimilar(Embedding queryEmbedding, int limit) {
            return chunks.values().stream()
                    .filter(KnowledgeBaseChunk::isIndexed)
                    .map(chunk -> toRetrieved(chunk, chunk.embedding().cosineSimilarity(queryEmbedding)))
                    .sorted(Comparator.comparingDouble(RetrievedChunk::similarity).reversed())
                    .limit(limit)
                    .toList();
        }

        public int size() {
            return chunks.size();
        }

        private RetrievedChunk toRetrieved(KnowledgeBaseChunk chunk, double similarity) {
            KnowledgeBaseSource source = sources.findById(chunk.sourceId()).orElseThrow();
            return new RetrievedChunk(
                    chunk.id(),
                    chunk.sourceId(),
                    source.title(),
                    source.sourceType(),
                    chunk.chunkIndex(),
                    chunk.content(),
                    similarity);
        }
    }
}
