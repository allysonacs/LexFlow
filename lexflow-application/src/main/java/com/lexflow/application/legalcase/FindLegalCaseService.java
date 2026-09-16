package com.lexflow.application.legalcase;

import com.lexflow.application.document.DocumentRepository;
import com.lexflow.domain.exception.LegalCaseNotFoundException;
import java.util.Objects;
import java.util.UUID;

/** Consulta de uma demanda e dos seus arquivos, usada pelo {@code GET /api/v1/legal-cases/{id}}. */
public class FindLegalCaseService {

    private final LegalCaseRepository legalCaseRepository;
    private final DocumentRepository documentRepository;

    public FindLegalCaseService(LegalCaseRepository legalCaseRepository, DocumentRepository documentRepository) {
        this.legalCaseRepository = Objects.requireNonNull(legalCaseRepository, "legalCaseRepository não pode ser nulo");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository não pode ser nulo");
    }

    /**
     * Busca a demanda pelo identificador.
     *
     * @throws LegalCaseNotFoundException se não existir demanda com esse identificador
     */
    public LegalCaseWithDocuments findById(UUID legalCaseId) {
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        return legalCaseRepository
                .findById(legalCaseId)
                .map(legalCase -> new LegalCaseWithDocuments(legalCase, documentRepository.findByLegalCaseId(legalCaseId)))
                .orElseThrow(() -> new LegalCaseNotFoundException(legalCaseId));
    }
}
