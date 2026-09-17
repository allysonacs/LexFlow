package com.lexflow.application.checklist;

import com.lexflow.application.document.DocumentRepository;
import com.lexflow.application.legalcase.LegalCaseRepository;
import com.lexflow.domain.checklist.ChecklistRule;
import com.lexflow.domain.checklist.DocumentChecklist;
import com.lexflow.domain.checklist.DocumentChecklistItem;
import com.lexflow.domain.exception.LegalCaseNotFoundException;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Geração, vínculo e consulta do checklist documental de uma demanda (Prompt 09, itens 3 a 6).
 *
 * <p>Tudo aqui é determinístico: nenhum LLM é chamado, e a mesma entrada produz sempre o mesmo
 * checklist.
 */
public class DocumentChecklistService {

    /** Status em que a demanda ainda não foi classificada e, portanto, não tem checklist. */
    private static final Set<LegalCaseStatus> NOT_YET_EVALUATED =
            Set.of(LegalCaseStatus.RECEIVED, LegalCaseStatus.CLASSIFYING);

    private final LegalCaseRepository legalCaseRepository;
    private final DocumentRepository documentRepository;
    private final ChecklistRuleRepository ruleRepository;
    private final DocumentChecklistItemRepository itemRepository;
    private final Clock clock;
    private final Supplier<UUID> idGenerator;

    public DocumentChecklistService(
            LegalCaseRepository legalCaseRepository,
            DocumentRepository documentRepository,
            ChecklistRuleRepository ruleRepository,
            DocumentChecklistItemRepository itemRepository,
            Clock clock,
            Supplier<UUID> idGenerator) {
        this.legalCaseRepository = Objects.requireNonNull(legalCaseRepository, "legalCaseRepository não pode ser nulo");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository não pode ser nulo");
        this.ruleRepository = Objects.requireNonNull(ruleRepository, "ruleRepository não pode ser nulo");
        this.itemRepository = Objects.requireNonNull(itemRepository, "itemRepository não pode ser nulo");
        this.clock = Objects.requireNonNull(clock, "clock não pode ser nulo");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator não pode ser nulo");
    }

    /**
     * Gera os itens que faltam para as regras do tipo da demanda e vincula os documentos enviados.
     *
     * <p>É chamado na classificação (Prompt 08), dentro da mesma transação que leva a demanda a
     * {@code EXTRACTING}, e pode ser chamado de novo sempre que a documentação mudar: itens existentes
     * não são recriados, e só os que mudaram de situação são gravados.
     *
     * <p>Deve rodar dentro de uma transação aberta por quem chama.
     */
    public DocumentChecklist synchronize(LegalCase legalCase) {
        Objects.requireNonNull(legalCase, "legalCase não pode ser nulo");
        List<DocumentChecklistItem> existing = itemRepository.findByLegalCaseId(legalCase.id());

        DocumentChecklist checklist = DocumentChecklist.synchronize(
                legalCase.id(),
                legalCase.caseType(),
                existing,
                rulesFor(legalCase, existing),
                documentRepository.findByLegalCaseId(legalCase.id()),
                idGenerator,
                clock.instant());

        Set<DocumentChecklistItem> unchanged = new HashSet<>(existing);
        List<DocumentChecklistItem> changed = checklist.items().stream()
                .filter(item -> !unchanged.contains(item))
                .toList();
        if (!changed.isEmpty()) {
            itemRepository.saveAll(changed);
        }
        return checklist;
    }

    /**
     * Checklist atual da demanda, sem alterar nada.
     *
     * @throws LegalCaseNotFoundException se a demanda não existir
     */
    public LegalCaseChecklist findByLegalCaseId(UUID legalCaseId) {
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        LegalCase legalCase = legalCaseRepository
                .findById(legalCaseId)
                .orElseThrow(() -> new LegalCaseNotFoundException(legalCaseId));
        List<DocumentChecklistItem> items = itemRepository.findByLegalCaseId(legalCaseId);
        Set<UUID> ruleIds = items.stream().map(DocumentChecklistItem::checklistRuleId).collect(Collectors.toSet());
        List<ChecklistRule> rules = ruleRepository.findAllById(ruleIds);
        DocumentChecklist unordered = new DocumentChecklist(items, rules);
        // Ordem de apresentação estável: obrigatórios primeiro, depois pelo código do documento.
        Comparator<DocumentChecklistItem> order = Comparator
                .comparing((DocumentChecklistItem item) -> !unordered.ruleOf(item).mandatory())
                .thenComparing(item -> unordered.ruleOf(item).requiredDocumentType());
        DocumentChecklist checklist =
                new DocumentChecklist(items.stream().sorted(order).toList(), rules);
        return new LegalCaseChecklist(legalCase, checklist, !NOT_YET_EVALUATED.contains(legalCase.status()));
    }

    /** Regras do tipo da demanda mais as já referenciadas pelos itens existentes. */
    private List<ChecklistRule> rulesFor(LegalCase legalCase, List<DocumentChecklistItem> existing) {
        Map<UUID, ChecklistRule> rules = new LinkedHashMap<>();
        ruleRepository.findByCaseType(legalCase.caseType()).forEach(rule -> rules.put(rule.id(), rule));
        Set<UUID> missing = existing.stream()
                .map(DocumentChecklistItem::checklistRuleId)
                .filter(ruleId -> !rules.containsKey(ruleId))
                .collect(Collectors.toSet());
        if (!missing.isEmpty()) {
            ruleRepository.findAllById(missing).forEach(rule -> rules.put(rule.id(), rule));
        }
        return List.copyOf(rules.values());
    }
}
