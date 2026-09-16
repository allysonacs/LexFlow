package com.lexflow.application.document;

import com.lexflow.domain.document.Document;
import java.util.List;
import java.util.UUID;

/** Porta de saída para os metadados dos arquivos anexados a uma demanda. */
public interface DocumentRepository {

    /** Grava os metadados de todos os arquivos de uma demanda. */
    void saveAll(List<Document> documents);

    /**
     * Arquivos de uma demanda, em ordem estável: data de envio, depois nome e identificador. A ordem
     * importa: quando dois documentos têm o tipo exigido por uma regra, o primeiro é o vinculado.
     */
    List<Document> findByLegalCaseId(UUID legalCaseId);
}
