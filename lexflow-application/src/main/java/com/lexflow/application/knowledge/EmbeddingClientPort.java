package com.lexflow.application.knowledge;

import com.lexflow.domain.knowledge.Embedding;
import java.util.List;

/**
 * Porta de saída do provedor de embeddings.
 *
 * <p>Separada da {@link com.lexflow.application.llm.LlmClientPort} de propósito: gerar embeddings e
 * gerar texto são chamadas diferentes, com modelos, limites e, muitas vezes, provedores diferentes —
 * a Anthropic, por exemplo, não expõe endpoint de embeddings. Manter as duas portas separadas permite
 * trocar uma sem tocar na outra.
 *
 * <p>A distinção entre documento e consulta não é enfeite: os modelos atuais são treinados para
 * projetar um trecho longo de norma e uma pergunta curta em pontos próximos do espaço vetorial, e
 * informar o papel de cada texto melhora sensivelmente a recuperação.
 */
public interface EmbeddingClientPort {

    /**
     * Dimensão dos vetores gerados. Precisa bater com a da coluna {@code vector} da migration.
     */
    int dimension();

    /** Embedding de um trecho que será indexado. */
    Embedding embedDocument(String text);

    /**
     * Embeddings de vários trechos, na mesma ordem da entrada.
     *
     * <p>A implementação decide como agrupar as chamadas; quem chama só precisa saber que a ordem é
     * preservada.
     */
    List<Embedding> embedDocuments(List<String> texts);

    /** Embedding de um texto de consulta. */
    Embedding embedQuery(String text);
}
