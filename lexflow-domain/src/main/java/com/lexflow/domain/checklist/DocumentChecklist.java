package com.lexflow.domain.checklist;

import com.lexflow.domain.document.Document;
import com.lexflow.domain.exception.ChecklistRuleNotFoundException;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Checklist documental completo de uma demanda: os itens avaliados e as regras correspondentes.
 *
 * <p>Responde de forma determinística à pergunta {@code HAS_SUFFICIENT_DOCUMENTATION} (seção 9):
 * a documentação só é suficiente quando todos os itens obrigatórios estão {@code SATISFIED}. Nenhum
 * LLM participa desta decisão.
 *
 * <p>Um checklist vazio é suficiente: significa que o tipo de demanda não exige documento algum.
 * Distinguir isso de um checklist que ainda não foi gerado é responsabilidade de quem consulta, que
 * conhece o status da demanda.
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

    /**
     * Sincroniza o checklist de uma demanda com as regras do seu tipo e com os documentos enviados.
     *
     * <ol>
     *   <li>Cada regra do tipo que ainda não tem item ganha um, que nasce {@code MISSING}. Itens já
     *       existentes são mantidos, o que torna a operação repetível sem duplicar nada.
     *   <li>Cada item é reavaliado: se algum documento tem o tipo exigido pela regra, o item fica
     *       {@code SATISFIED} e aponta para o primeiro deles, na ordem informada; caso contrário, fica
     *       {@code MISSING}. Um item cuja situação não mudou é devolvido intacto, com a data da
     *       avaliação anterior.
     * </ol>
     *
     * @param rules regras do tipo de demanda e também as referenciadas pelos itens existentes; as de
     *     outros tipos são ignoradas na criação de itens
     * @param documents documentos da demanda, na ordem de envio
     * @throws ChecklistRuleNotFoundException se um item existente apontar para uma regra ausente
     * @throws IllegalArgumentException se um item ou documento pertencer a outra demanda
     */
    public static DocumentChecklist synchronize(
            UUID legalCaseId,
            LegalCaseType caseType,
            Collection<DocumentChecklistItem> existingItems,
            Collection<ChecklistRule> rules,
            List<Document> documents,
            Supplier<UUID> idGenerator,
            Instant evaluatedAt) {
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        Objects.requireNonNull(caseType, "caseType não pode ser nulo");
        Objects.requireNonNull(idGenerator, "idGenerator não pode ser nulo");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt não pode ser nulo");
        requireSameCase(legalCaseId, existingItems, DocumentChecklistItem::legalCaseId, "item");
        requireSameCase(legalCaseId, documents, Document::legalCaseId, "documento");

        List<DocumentChecklistItem> items = new ArrayList<>(existingItems);
        Set<UUID> coveredRules = new HashSet<>();
        existingItems.forEach(item -> coveredRules.add(item.checklistRuleId()));
        for (ChecklistRule rule : rules) {
            if (rule.caseType() == caseType && coveredRules.add(rule.id())) {
                items.add(DocumentChecklistItem.missing(idGenerator.get(), legalCaseId, rule.id(), evaluatedAt));
            }
        }

        DocumentChecklist generated = new DocumentChecklist(items, rules);
        List<DocumentChecklistItem> evaluated = generated.items.stream()
                .map(item -> evaluate(item, generated.ruleOf(item), documents, evaluatedAt))
                .toList();
        return new DocumentChecklist(evaluated, rules);
    }

    private static DocumentChecklistItem evaluate(
            DocumentChecklistItem item, ChecklistRule rule, List<Document> documents, Instant evaluatedAt) {
        Optional<Document> match = documents.stream().filter(rule::isSatisfiedBy).findFirst();
        if (match.isPresent()) {
            UUID documentId = match.get().id();
            return item.isSatisfied() && documentId.equals(item.documentId())
                    ? item
                    : item.satisfyWith(documentId, evaluatedAt);
        }
        return item.status() == ChecklistItemStatus.MISSING ? item : item.markMissing(evaluatedAt);
    }

    private static <T> void requireSameCase(
            UUID legalCaseId, Collection<T> elements, Function<T, UUID> caseIdOf, String label) {
        Objects.requireNonNull(elements, label + "s não pode ser nulo");
        for (T element : elements) {
            if (!legalCaseId.equals(caseIdOf.apply(element))) {
                throw new IllegalArgumentException("%s pertence a outra demanda".formatted(label));
            }
        }
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

    /** Códigos dos documentos obrigatórios que ainda faltam, na ordem dos itens. */
    public List<String> missingMandatoryDocumentTypes() {
        return missingMandatoryItems().stream()
                .map(item -> requireRule(item.checklistRuleId()).requiredDocumentType())
                .toList();
    }

    /** Quantidade de itens obrigatórios. */
    public long mandatoryItemCount() {
        return items.stream().filter(item -> requireRule(item.checklistRuleId()).mandatory()).count();
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
