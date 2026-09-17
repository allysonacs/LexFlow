package com.lexflow.application.checklist;

import com.lexflow.domain.checklist.DocumentChecklist;
import com.lexflow.domain.legalcase.LegalCase;
import java.util.Objects;

/**
 * Checklist de uma demanda, como a consulta o apresenta.
 *
 * @param evaluated {@code false} enquanto a demanda não passou pela classificação — o checklist ainda
 *     não foi gerado, e um checklist vazio não pode ser lido como "nada é exigido"
 */
public record LegalCaseChecklist(LegalCase legalCase, DocumentChecklist checklist, boolean evaluated) {

    public LegalCaseChecklist {
        Objects.requireNonNull(legalCase, "legalCase não pode ser nulo");
        Objects.requireNonNull(checklist, "checklist não pode ser nulo");
    }

    /**
     * Resposta determinística a {@code HAS_SUFFICIENT_DOCUMENTATION} (seção 9).
     *
     * <p>Verdadeira apenas quando o checklist já foi gerado e todo item obrigatório está
     * {@code SATISFIED}. Não depende do status da demanda além disso: avançar o pipeline não torna
     * suficiente uma documentação incompleta.
     */
    public boolean hasSufficientDocumentation() {
        return evaluated && checklist.hasSufficientDocumentation();
    }
}
