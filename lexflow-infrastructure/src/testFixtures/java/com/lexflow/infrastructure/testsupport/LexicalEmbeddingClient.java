package com.lexflow.infrastructure.testsupport;

import com.lexflow.application.knowledge.EmbeddingClientPort;
import com.lexflow.domain.knowledge.Embedding;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Modelo de embeddings determinístico usado nos testes: nenhum teste automatizado chama o provedor
 * real de embeddings, pela mesma razão que nenhum chama o LLM real (Prompt 11).
 *
 * <p>Cada palavra do texto soma um em uma dimensão do vetor, escolhida pelo hash da palavra. Não é um
 * modelo de verdade — não capta sinônimo nem paráfrase —, mas reproduz a propriedade de que a
 * recuperação depende: textos que falam das mesmas coisas ficam próximos no espaço vetorial, e textos
 * sobre assuntos diferentes ficam distantes. Como é determinístico, o resultado do teste não depende
 * de rede, de chave de API nem da versão do modelo do provedor.
 *
 * <p>A dimensão é a mesma da coluna {@code vector} da migration, para que o vetor gerado aqui seja
 * aceito pelo banco exatamente como o do provedor real seria.
 */
public class LexicalEmbeddingClient implements EmbeddingClientPort {

    /** Dimensão da coluna {@code knowledge_base_chunks.embedding}. */
    public static final int DIMENSION = 1536;

    /** Palavras curtas demais são ruído: preposições e artigos aparecem em qualquer norma. */
    private static final int MIN_TOKEN_LENGTH = 3;

    @Override
    public int dimension() {
        return DIMENSION;
    }

    @Override
    public Embedding embedDocument(String text) {
        return embed(text);
    }

    @Override
    public List<Embedding> embedDocuments(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    @Override
    public Embedding embedQuery(String text) {
        return embed(text);
    }

    private Embedding embed(String text) {
        float[] values = new float[DIMENSION];
        for (String token : normalize(text).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= MIN_TOKEN_LENGTH) {
                values[Math.floorMod(token.hashCode(), DIMENSION)] += 1.0f;
            }
        }
        // O pgvector não calcula distância de cosseno com um vetor nulo; este valor garante direção.
        values[0] += 0.001f;
        return Embedding.of(values);
    }

    /** Minúsculas e sem acento, para que "alçada" e "alcada" caiam na mesma dimensão. */
    private static String normalize(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }
}
