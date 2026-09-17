package com.lexflow.application.checklist;

import com.lexflow.application.exception.ApplicationException;
import java.util.UUID;

/**
 * Lançada ao tentar excluir, ou mudar o documento exigido de, uma regra que já gerou itens de
 * checklist.
 *
 * <p>Os itens existentes apontam para a regra e registram uma avaliação feita com ela; apagá-la ou
 * mudar o que ela exige reescreveria o passado dessas demandas. Para deixar de exigir um documento,
 * a regra deve ser marcada como não obrigatória.
 */
public class ChecklistRuleInUseException extends ApplicationException {

    public ChecklistRuleInUseException(UUID checklistRuleId) {
        super("A regra de checklist %s já está em uso por demandas; torne-a opcional em vez de excluí-la ou trocar o documento exigido"
                .formatted(checklistRuleId));
    }
}
