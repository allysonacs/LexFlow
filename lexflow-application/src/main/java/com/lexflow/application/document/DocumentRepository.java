package com.lexflow.application.document;

import com.lexflow.domain.document.Document;
import java.util.List;
import java.util.UUID;

/** Porta de saída para os metadados dos arquivos anexados a uma demanda. */
public interface DocumentRepository {

    /** Grava os metadados de todos os arquivos de uma demanda. */
    void saveAll(List<Document> documents);

    /** Arquivos de uma demanda, na ordem em que foram enviados. */
    List<Document> findByLegalCaseId(UUID legalCaseId);
}
