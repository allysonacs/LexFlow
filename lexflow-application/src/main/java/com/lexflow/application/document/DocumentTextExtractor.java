package com.lexflow.application.document;

import com.lexflow.domain.document.DocumentFormat;

/**
 * Porta de saída que transforma o binário de um documento em texto.
 *
 * <p>A implementação decide como: lendo a camada de texto do arquivo quando ela existe, ou recorrendo
 * a OCR quando o documento é uma imagem ou um PDF digitalizado. Nenhum LLM participa desta etapa.
 *
 * <p>As duas exceções têm consequências diferentes, e a distinção é deliberada:
 *
 * <ul>
 *   <li>{@link com.lexflow.application.exception.UnreadableDocumentException}: o problema é o
 *       arquivo. Tentar de novo não adianta; o documento é registrado como falho e o pipeline segue.
 *   <li>{@link com.lexflow.application.exception.DocumentTextExtractionException}: o problema é o
 *       ambiente (OCR ausente, tempo esgotado). A falha sobe, e a mensagem volta para a fila.
 * </ul>
 */
public interface DocumentTextExtractor {

    /**
     * Extrai o texto de um documento.
     *
     * @param format formato já validado na ingestão
     * @param content binário do arquivo
     * @throws com.lexflow.application.exception.UnreadableDocumentException se o arquivo estiver
     *     corrompido, protegido ou não puder ser interpretado
     * @throws com.lexflow.application.exception.DocumentTextExtractionException se a extração não
     *     puder ser feita por um problema de ambiente
     */
    ExtractedText extract(DocumentFormat format, byte[] content);
}
