package com.lexflow.application.legalcase;

import com.lexflow.domain.document.Document;
import com.lexflow.domain.legalcase.LegalCase;
import java.util.List;
import java.util.Objects;

/** Demanda acompanhada dos metadados dos seus arquivos, como a API precisa apresentá-la. */
public record LegalCaseWithDocuments(LegalCase legalCase, List<Document> documents) {

    public LegalCaseWithDocuments {
        Objects.requireNonNull(legalCase, "legalCase não pode ser nulo");
        documents = List.copyOf(Objects.requireNonNull(documents, "documents não pode ser nulo"));
    }
}
