package com.lexflow.application.checklist;

import com.lexflow.domain.checklist.DocumentChecklistItem;
import java.util.List;
import java.util.UUID;

/**
 * Porta de saída dos itens de checklist de cada demanda.
 *
 * <p>O banco garante no máximo um item por regra em cada demanda.
 */
public interface DocumentChecklistItemRepository {

    /** Itens de uma demanda, na ordem em que foram criados. */
    List<DocumentChecklistItem> findByLegalCaseId(UUID legalCaseId);

    /** Grava itens novos e atualiza os existentes. */
    void saveAll(List<DocumentChecklistItem> items);

    /** Indica se alguma demanda já usa a regra — caso em que ela não pode ser excluída. */
    boolean existsByChecklistRuleId(UUID checklistRuleId);
}
