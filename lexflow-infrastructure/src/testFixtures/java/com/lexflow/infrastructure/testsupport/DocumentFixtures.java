package com.lexflow.infrastructure.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Acesso aos documentos de teste em {@code fixtures/documents}, compartilhados entre os módulos.
 *
 * <p>Todos têm conteúdo fictício:
 *
 * <ul>
 *   <li>{@code contrato-texto-nativo.pdf}: PDF com camada de texto (contrato de prestação de serviços);
 *   <li>{@code peticao-digitalizada.pdf}: PDF só com imagem, sem camada de texto (petição de
 *       encerramento) — exige OCR;
 *   <li>{@code termo-de-acordo.png}: imagem de um termo de acordo — exige OCR;
 *   <li>{@code proposta-comercial.jpg}: imagem de uma proposta comercial — exige OCR;
 *   <li>{@code minuta-aditivo.docx}: minuta de termo aditivo em DOCX.
 * </ul>
 *
 * <p>Em {@code fixtures/facts} ficam os gabaritos da extração de fatos (Prompt 11): o JSON que se
 * espera obter de cada documento, no formato de {@code FactExtractionSchema}.
 */
public final class DocumentFixtures {

    private DocumentFixtures() {
        // classe utilitária
    }

    /** Gabarito da extração de fatos do contrato {@code contrato-texto-nativo.pdf}. */
    public static final String CONTRACT_FACTS_ANSWER_KEY = "contrato-texto-nativo.gabarito.json";

    /** Lê o binário de uma fixture pelo nome do arquivo. */
    public static byte[] read(String fileName) {
        return readResource("/fixtures/documents/" + fileName);
    }

    /** Lê um gabarito de extração de fatos. */
    public static String readFactsAnswerKey(String fileName) {
        return new String(readResource("/fixtures/facts/" + fileName), StandardCharsets.UTF_8);
    }

    private static byte[] readResource(String path) {
        try (InputStream stream = DocumentFixtures.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalArgumentException("fixture inexistente: " + path);
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
