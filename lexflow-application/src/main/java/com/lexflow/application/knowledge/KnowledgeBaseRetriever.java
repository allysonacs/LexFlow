package com.lexflow.application.knowledge;

import com.lexflow.domain.knowledge.Embedding;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Recupera os trechos normativos mais relevantes para uma consulta (Prompt 12, item 4).
 *
 * <p><strong>Este componente não decide nada juridicamente.</strong> Ele traduz o texto da consulta em
 * um vetor, pergunta ao banco quais trechos estão mais próximos e devolve o que encontrou. Quem
 * transforma isso em resposta é a cadeia de prompts (Prompt 13), e quem decide é o revisor humano.
 *
 * <p>O corte por similaridade mínima existe para não empurrar ruído ao modelo: a busca vetorial sempre
 * devolve os N mais próximos, mesmo quando nenhum trata do assunto. Um trecho irrelevante no contexto
 * é um convite à alucinação — é preferível o modelo responder "informação não encontrada na base
 * normativa" (seção 10, item 6).
 */
public class KnowledgeBaseRetriever {

    private final EmbeddingClientPort embeddingClient;
    private final KnowledgeBaseChunkRepository chunkRepository;
    private final int defaultLimit;
    private final double minSimilarity;

    /**
     * @param defaultLimit quantos trechos são recuperados quando a chamada não informa outro número
     * @param minSimilarity similaridade de cosseno abaixo da qual o trecho é descartado; zero desliga
     *     o corte
     */
    public KnowledgeBaseRetriever(
            EmbeddingClientPort embeddingClient,
            KnowledgeBaseChunkRepository chunkRepository,
            int defaultLimit,
            double minSimilarity) {
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "embeddingClient não pode ser nulo");
        this.chunkRepository = Objects.requireNonNull(chunkRepository, "chunkRepository não pode ser nulo");
        if (defaultLimit < 1) {
            throw new IllegalArgumentException("defaultLimit deve ser positivo");
        }
        if (minSimilarity < -1.0 || minSimilarity > 1.0) {
            throw new IllegalArgumentException("minSimilarity deve estar entre -1.0 e 1.0");
        }
        this.defaultLimit = defaultLimit;
        this.minSimilarity = minSimilarity;
    }

    /** Recupera os trechos mais relevantes, na quantidade padrão. */
    public List<RetrievedChunk> retrieve(String queryText) {
        return retrieve(queryText, defaultLimit);
    }

    /**
     * Recupera os trechos mais relevantes para o texto da consulta.
     *
     * @param queryText pergunta jurídica somada ao contexto da demanda
     * @param limit quantidade máxima de trechos
     * @return os trechos, do mais próximo ao mais distante; vazia quando a base não tem nada
     *     suficientemente próximo
     * @throws EmbeddingUnavailableException se o provedor de embeddings estiver indisponível
     */
    public List<RetrievedChunk> retrieve(String queryText, int limit) {
        if (queryText == null || queryText.isBlank()) {
            throw new IllegalArgumentException("queryText é obrigatório");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit deve ser positivo");
        }
        Embedding query = embeddingClient.embedQuery(queryText);
        return chunkRepository.findMostSimilar(query, limit).stream()
                .filter(chunk -> chunk.similarity() >= minSimilarity)
                .toList();
    }

    /**
     * Texto dos trechos citados por uma resposta já gravada, para exibi-los ao revisor humano.
     *
     * <p>A ordem devolvida é a dos identificadores pedidos, que é a ordem em que a IA os citou.
     */
    public List<RetrievedChunk> findCited(Collection<java.util.UUID> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> found = chunkRepository.findAllById(chunkIds);
        return chunkIds.stream()
                .map(id -> found.stream().filter(chunk -> chunk.chunkId().equals(id)).findFirst().orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }
}
