package com.lexflow.application.checklist;

import com.lexflow.application.pagination.PageQuery;
import com.lexflow.application.pagination.PageResult;
import com.lexflow.domain.checklist.ChecklistRule;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída das regras de checklist — a configuração de negócio que diz quais documentos cada
 * tipo de demanda exige.
 *
 * <p>O banco tem índice único em {@code (case_type, required_document_type)}; a implementação traduz
 * a violação dele em {@link DuplicateChecklistRuleException}, e a de chave estrangeira na exclusão em
 * {@link ChecklistRuleInUseException}.
 */
public interface ChecklistRuleRepository {

    ChecklistRule save(ChecklistRule rule);

    Optional<ChecklistRule> findById(UUID id);

    /** Regras de um tipo de demanda, ordenadas pelo código do documento exigido. */
    List<ChecklistRule> findByCaseType(LegalCaseType caseType);

    List<ChecklistRule> findAllById(Collection<UUID> ids);

    /**
     * Listagem paginada, ordenada por tipo de demanda e código do documento.
     *
     * @param caseType filtro opcional; nulo lista todos os tipos
     */
    PageResult<ChecklistRule> findAll(LegalCaseType caseType, PageQuery pageQuery);

    boolean existsByCaseTypeAndRequiredDocumentType(LegalCaseType caseType, String requiredDocumentType);

    void deleteById(UUID id);
}
