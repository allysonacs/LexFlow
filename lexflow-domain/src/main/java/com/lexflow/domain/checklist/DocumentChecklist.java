package com.lexflow.domain.checklist;

import com.lexflow.domain.exception.ChecklistRuleNotFoundException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Checklist documental completo de uma demanda: os itens avaliados e as regras correspondentes.
 *
 * <p>Responde de forma determinística à pergunta {@code HAS_SUFFICIENT_DOCUMENTATION} (seção 9):
 * a documentação só é suficiente quando todos os itens obrigatórios estão {@code SATISFIED}. Nenhum
 * LLM participa desta decisão.
 */
public final class DocumentChecklist {

    private final List<DocumentChecklistItem> items;
    private final Map<UUID, ChecklistRule> rulesById;

    public DocumentChecklist(Collection<DocumentChecklistItem> items, Collection<ChecklistRule> rules) {
        Objects.requireNonNull(items, "items não pode ser nulo");
        Objects.requireNonNull(rules, "rules não pode ser nulo");
        this.items = List.copyOf(items);
        this.rulesById = new HashMap<>(rules.stream()
                .collect(Collectors.toMap(ChecklistRule::id, Function.identity(), (first, second) -> first)));
        this.items.forEach(item -> requireRule(item.checklistRuleId()));
    }

    /** Itens do checklist, na ordem em que foram informados. */
    public List<DocumentChecklistItem> items() {
        return items;
    }

    /**
     * Regra à qual o item se refere.
     *
     * @throws ChecklistRuleNotFoundException se a regra não estiver presente no checklist
     */
    public ChecklistRule ruleOf(DocumentChecklistItem item) {
        return requireRule(item.checklistRuleId());
    }

    /** Itens obrigatórios que ainda não foram satisfeitos. */
    public List<DocumentChecklistItem> missingMandatoryItems() {
        return items.stream()
                .filter(item -> requireRule(item.checklistRuleId()).mandatory())
                .filter(item -> !item.isSatisfied())
                .toList();
    }

    /**
     * Resposta determinística a {@code HAS_SUFFICIENT_DOCUMENTATION}: verdadeiro apenas quando todo
     * item obrigatório está satisfeito.
     */
    public boolean hasSufficientDocumentation() {
        return missingMandatoryItems().isEmpty();
    }

    private ChecklistRule requireRule(UUID checklistRuleId) {
        ChecklistRule rule = rulesById.get(checklistRuleId);
        if (rule == null) {
            throw new ChecklistRuleNotFoundException(checklistRuleId);
        }
        return rule;
    }
}
