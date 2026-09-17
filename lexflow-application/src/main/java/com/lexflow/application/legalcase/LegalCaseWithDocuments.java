package com.lexflow.application.legalcase;

import com.lexflow.domain.alert.LegalCaseAlert;
import com.lexflow.domain.document.Document;
import com.lexflow.domain.legalcase.LegalCase;
import java.util.List;
import java.util.Objects;

/**
 * Demanda acompanhada dos metadados dos seus arquivos e dos seus alertas, como a API precisa
 * apresentá-la.
 */
public record LegalCaseWithDocuments(LegalCase legalCase, List<Document> documents, List<LegalCaseAlert> alerts) {

    public LegalCaseWithDocuments {
        Objects.requireNonNull(legalCase, "legalCase não pode ser nulo");
        documents = List.copyOf(Objects.requireNonNull(documents, "documents não pode ser nulo"));
        alerts = List.copyOf(Objects.requireNonNull(alerts, "alerts não pode ser nulo"));
    }

    /** Demanda sem alertas — o caso de uma demanda recém-recebida. */
    public LegalCaseWithDocuments(LegalCase legalCase, List<Document> documents) {
        this(legalCase, documents, List.of());
    }
}
