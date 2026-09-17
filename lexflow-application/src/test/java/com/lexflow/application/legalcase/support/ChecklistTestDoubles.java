package com.lexflow.application.legalcase.support;

import com.lexflow.application.checklist.ChecklistRuleInUseException;
import com.lexflow.application.checklist.ChecklistRuleRepository;
import com.lexflow.application.checklist.DocumentChecklistItemRepository;
import com.lexflow.application.checklist.DuplicateChecklistRuleException;
import com.lexflow.application.pagination.PageQuery;
import com.lexflow.application.pagination.PageResult;
import com.lexflow.domain.checklist.ChecklistRule;
import com.lexflow.domain.checklist.DocumentChecklistItem;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Dublês das portas do checklist, com as mesmas restrições de unicidade e de uso do banco. */
public final class ChecklistTestDoubles {

    private ChecklistTestDoubles() {
        // classe utilitária
    }

    /** Regras em memória. */
    public static class InMemoryChecklistRuleRepository implements ChecklistRuleRepository {

        private final Map<UUID, ChecklistRule> rules = new LinkedHashMap<>();
        private InMemoryDocumentChecklistItemRepository items;

        /** Liga ao repositório de itens para simular a chave estrangeira na exclusão. */
        public InMemoryChecklistRuleRepository referencedBy(InMemoryDocumentChecklistItemRepository items) {
            this.items = items;
            return this;
        }

        @Override
        public ChecklistRule save(ChecklistRule rule) {
            boolean duplicated = rules.values().stream()
                    .anyMatch(other -> !other.id().equals(rule.id())
                            && other.caseType() == rule.caseType()
                            && other.requiredDocumentType().equals(rule.requiredDocumentType()));
            if (duplicated) {
                throw new DuplicateChecklistRuleException(rule.caseType(), rule.requiredDocumentType());
            }
            rules.put(rule.id(), rule);
            return rule;
        }

        @Override
        public Optional<ChecklistRule> findById(UUID id) {
            return Optional.ofNullable(rules.get(id));
        }

        @Override
        public List<ChecklistRule> findByCaseType(LegalCaseType caseType) {
            return rules.values().stream()
                    .filter(rule -> rule.caseType() == caseType)
                    .sorted(Comparator.comparing(ChecklistRule::requiredDocumentType))
                    .toList();
        }

        @Override
        public List<ChecklistRule> findAllById(Collection<UUID> ids) {
            return ids.stream().map(rules::get).filter(rule -> rule != null).toList();
        }

        @Override
        public PageResult<ChecklistRule> findAll(LegalCaseType caseType, PageQuery pageQuery) {
            List<ChecklistRule> filtered = rules.values().stream()
                    .filter(rule -> caseType == null || rule.caseType() == caseType)
                    .sorted(Comparator.comparing(ChecklistRule::caseType)
                            .thenComparing(ChecklistRule::requiredDocumentType))
                    .toList();
            List<ChecklistRule> page = filtered.stream()
                    .skip((long) pageQuery.page() * pageQuery.size())
                    .limit(pageQuery.size())
                    .toList();
            return new PageResult<>(page, pageQuery.page(), pageQuery.size(), filtered.size());
        }

        @Override
        public boolean existsByCaseTypeAndRequiredDocumentType(LegalCaseType caseType, String requiredDocumentType) {
            return rules.values().stream()
                    .anyMatch(rule -> rule.caseType() == caseType
                            && rule.requiredDocumentType().equals(requiredDocumentType));
        }

        @Override
        public void deleteById(UUID id) {
            if (items != null && items.existsByChecklistRuleId(id)) {
                throw new ChecklistRuleInUseException(id);
            }
            rules.remove(id);
        }

        public int count() {
            return rules.size();
        }
    }

    /** Itens em memória, com no máximo um item por regra em cada demanda. */
    public static final class InMemoryDocumentChecklistItemRepository implements DocumentChecklistItemRepository {

        private final Map<UUID, DocumentChecklistItem> items = new LinkedHashMap<>();
        private final List<List<DocumentChecklistItem>> saveCalls = new ArrayList<>();

        @Override
        public List<DocumentChecklistItem> findByLegalCaseId(UUID legalCaseId) {
            return items.values().stream().filter(item -> item.legalCaseId().equals(legalCaseId)).toList();
        }

        @Override
        public void saveAll(List<DocumentChecklistItem> toSave) {
            for (DocumentChecklistItem item : toSave) {
                boolean duplicated = items.values().stream()
                        .anyMatch(other -> !other.id().equals(item.id())
                                && other.legalCaseId().equals(item.legalCaseId())
                                && other.checklistRuleId().equals(item.checklistRuleId()));
                if (duplicated) {
                    throw new IllegalStateException("item duplicado para a regra " + item.checklistRuleId());
                }
                items.put(item.id(), item);
            }
            saveCalls.add(List.copyOf(toSave));
        }

        @Override
        public boolean existsByChecklistRuleId(UUID checklistRuleId) {
            return items.values().stream().anyMatch(item -> item.checklistRuleId().equals(checklistRuleId));
        }

        /** Itens gravados em cada chamada, para verificar que só o que mudou é regravado. */
        public List<List<DocumentChecklistItem>> saveCalls() {
            return List.copyOf(saveCalls);
        }

        public List<DocumentChecklistItem> all() {
            return List.copyOf(items.values());
        }
    }

    /**
     * Regras equivalentes às do seed ({@code V5__seed_checklist_rules.sql}): duas obrigatórias e uma
     * opcional por tipo de demanda.
     */
    public static List<ChecklistRule> seedRules() {
        List<ChecklistRule> rules = new ArrayList<>();
        rules.add(rule(LegalCaseType.SUPPLIER_HIRING, "SUPPLIER_CNPJ_CARD", true));
        rules.add(rule(LegalCaseType.SUPPLIER_HIRING, "SUPPLIER_QUALIFICATION_DOCUMENTS", true));
        rules.add(rule(LegalCaseType.SUPPLIER_HIRING, "COMMERCIAL_PROPOSAL", false));
        rules.add(rule(LegalCaseType.CONTRACT_SIGNING, "CONTRACT_DRAFT", true));
        rules.add(rule(LegalCaseType.CONTRACT_SIGNING, "FINANCIAL_OPINION", true));
        rules.add(rule(LegalCaseType.CONTRACT_SIGNING, "SIGNATORY_POWERS", false));
        rules.add(rule(LegalCaseType.SETTLEMENT_PAYMENT, "SETTLEMENT_AGREEMENT", true));
        rules.add(rule(LegalCaseType.SETTLEMENT_PAYMENT, "PAYMENT_INSTRUCTIONS", true));
        rules.add(rule(LegalCaseType.SETTLEMENT_PAYMENT, "COURT_APPROVAL", false));
        rules.add(rule(LegalCaseType.LAWSUIT_CLOSURE, "CLOSURE_PETITION", true));
        rules.add(rule(LegalCaseType.LAWSUIT_CLOSURE, "CASE_PROGRESS_REPORT", true));
        rules.add(rule(LegalCaseType.LAWSUIT_CLOSURE, "FINAL_JUDGMENT", false));
        rules.add(rule(LegalCaseType.PROPOSAL_ACCEPTANCE, "PROPOSAL_DOCUMENT", true));
        rules.add(rule(LegalCaseType.PROPOSAL_ACCEPTANCE, "FINANCIAL_OPINION", true));
        rules.add(rule(LegalCaseType.PROPOSAL_ACCEPTANCE, "COUNTERPARTY_REGISTRATION", false));
        return List.copyOf(rules);
    }

    private static ChecklistRule rule(LegalCaseType type, String documentType, boolean mandatory) {
        return new ChecklistRule(UUID.randomUUID(), type, documentType, "Regra " + documentType, mandatory);
    }
}
