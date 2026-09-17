package com.lexflow.application.document;

import com.lexflow.domain.document.DocumentTextContent;
import java.util.List;
import java.util.UUID;

/**
 * Porta de saída para o texto extraído dos documentos.
 *
 * <p>Cada documento tem no máximo um registro — o banco garante isso com um índice único. É o que
 * permite retomar uma extração interrompida sem repetir os documentos que já foram lidos.
 */
public interface DocumentTextContentRepository {

    /** Grava o texto extraído de um documento. */
    void save(DocumentTextContent textContent);

    /** Textos já extraídos dos documentos de uma demanda. */
    List<DocumentTextContent> findByLegalCaseId(UUID legalCaseId);
}
