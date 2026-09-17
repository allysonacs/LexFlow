package com.lexflow.api.regression;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Carrega os casos dourados de {@code src/test/resources/golden-cases}.
 *
 * <p>Cada caso é uma pasta com um {@code case.json} e os arquivos de norma que ele indexa. Acrescentar
 * um caso é criar uma pasta — nenhum código precisa ser alterado, e é isso que mantém o dataset vivo:
 * quem encontra uma resposta ruim em produção consegue transformá-la em caso de regressão sem abrir
 * uma classe Java.
 */
public final class GoldenCases {

    /** Raiz dos casos no classpath de teste. */
    public static final String ROOT = "/golden-cases";

    /** Arquivo que descreve cada caso. */
    public static final String DESCRIPTOR = "case.json";

    /**
     * Casos disponíveis, em ordem estável.
     *
     * <p>A lista de pastas é declarada em {@code golden-cases/index.txt}: varrer o classpath em busca
     * de diretórios funciona de formas diferentes dentro e fora de um jar, e um índice explícito
     * também documenta o dataset.
     */
    public static List<GoldenCase> loadAll(ObjectMapper objectMapper) {
        List<GoldenCase> cases = new ArrayList<>();
        for (String folder : readIndex()) {
            cases.add(load(objectMapper, folder));
        }
        cases.sort(Comparator.comparing(GoldenCase::id));
        return List.copyOf(cases);
    }

    /** Lê um caso específico. */
    public static GoldenCase load(ObjectMapper objectMapper, String folder) {
        try {
            return objectMapper.readValue(read(folder + "/" + DESCRIPTOR), GoldenCase.class);
        } catch (IOException e) {
            throw new UncheckedIOException("golden case inválido: " + folder, e);
        }
    }

    /** Texto de um arquivo do caso, como a norma que ele indexa. */
    public static String readText(String folder, String file) {
        return new String(read(folder + "/" + file), StandardCharsets.UTF_8);
    }

    private static List<String> readIndex() {
        String index = new String(read("index.txt"), StandardCharsets.UTF_8);
        return index.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    private static byte[] read(String path) {
        try (InputStream stream = GoldenCases.class.getResourceAsStream(ROOT + "/" + path)) {
            if (stream == null) {
                throw new IllegalArgumentException("arquivo de golden case inexistente: " + ROOT + "/" + path);
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private GoldenCases() {
        // classe utilitária
    }
}
