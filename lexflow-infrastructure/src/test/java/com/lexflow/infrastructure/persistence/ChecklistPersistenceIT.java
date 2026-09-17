package com.lexflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.application.checklist.ChecklistRuleInUseException;
import com.lexflow.application.checklist.DuplicateChecklistRuleException;
import com.lexflow.application.pagination.PageQuery;
import com.lexflow.application.pagination.PageResult;
import com.lexflow.domain.checklist.ChecklistRule;
import com.lexflow.domain.checklist.DocumentChecklistItem;
import com.lexflow.domain.document.Document;
import com.lexflow.domain.document.Sha256Checksum;
import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseType;
import com.lexflow.infrastructure.persistence.adapter.ChecklistRuleRepositoryAdapter;
import com.lexflow.infrastructure.persistence.adapter.DocumentChecklistItemRepositoryAdapter;
import com.lexflow.infrastructure.persistence.adapter.DocumentRepositoryAdapter;
import com.lexflow.infrastructure.persistence.mapper.LegalCaseMapper;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Checklist documental (Prompt 09) contra um PostgreSQL real: seed inicial, restrições de unicidade e
 * de uso, paginação e o tipo de documento gravado com o upload.
 */
@Import({
    ChecklistRuleRepositoryAdapter.class,
    DocumentChecklistItemRepositoryAdapter.class,
    DocumentRepositoryAdapter.class
})
class ChecklistPersistenceIT extends AbstractPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    @Autowired
    private ChecklistRuleRepositoryAdapter ruleRepository;

    @Autowired
    private DocumentChecklistItemRepositoryAdapter itemRepository;

    @Autowired
    private DocumentRepositoryAdapter documentRepository;

    @Autowired
    private LegalCaseJpaRepository legalCaseRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("o seed traz regras para todo tipo de demanda, com ao menos uma obrigatória")
    void shouldSeedRulesForEveryCaseType() {
        for (LegalCaseType type : LegalCaseType.values()) {
            List<ChecklistRule> rules = ruleRepository.findByCaseType(type);
            assertThat(rules).as("regras de %s", type).isNotEmpty().anyMatch(ChecklistRule::mandatory);
            assertThat(rules)
                    .extracting(ChecklistRule::requiredDocumentType)
                    .isSorted();
        }
        assertThat(ruleRepository.findByCaseType(LegalCaseType.CONTRACT_SIGNING))
                .filteredOn(ChecklistRule::mandatory)
                .extracting(ChecklistRule::requiredDocumentType)
                .containsExactly("CONTRACT_DRAFT", "FINANCIAL_OPINION");
        assertThat(ruleRepository.findByCaseType(LegalCaseType.SUPPLIER_HIRING))
                .extracting(ChecklistRule::requiredDocumentType)
                .contains("SUPPLIER_CNPJ_CARD");
    }

    @Test
    @DisplayName("o índice único impede a mesma regra duas vezes no mesmo tipo")
    void shouldRejectDuplicateRule() {
        ChecklistRule duplicate =
                new ChecklistRule(UUID.randomUUID(), LegalCaseType.CONTRACT_SIGNING, "CONTRACT_DRAFT", null, false);

        assertThat(ruleRepository.existsByCaseTypeAndRequiredDocumentType(
                        LegalCaseType.CONTRACT_SIGNING, "CONTRACT_DRAFT"))
                .isTrue();
        assertThatExceptionOfType(DuplicateChecklistRuleException.class)
                .isThrownBy(() -> ruleRepository.save(duplicate));
    }

    @Test
    @DisplayName("regra usada por uma demanda não pode ser excluída; regra sem uso pode")
    void shouldGuardDeletionOfRuleInUse() {
        ChecklistRule used = ruleRepository.save(
                new ChecklistRule(UUID.randomUUID(), LegalCaseType.LAWSUIT_CLOSURE, "TEST_USED_RULE", null, true));
        ChecklistRule unused = ruleRepository.save(
                new ChecklistRule(UUID.randomUUID(), LegalCaseType.LAWSUIT_CLOSURE, "TEST_UNUSED_RULE", null, true));
        LegalCase legalCase = persistedCase();
        itemRepository.saveAll(List.of(DocumentChecklistItem.missing(UUID.randomUUID(), legalCase.id(), used.id(), NOW)));
        flushAndClear();

        assertThat(itemRepository.existsByChecklistRuleId(used.id())).isTrue();
        ruleRepository.deleteById(unused.id());
        assertThat(ruleRepository.findById(unused.id())).isEmpty();
        assertThatExceptionOfType(ChecklistRuleInUseException.class)
                .isThrownBy(() -> ruleRepository.deleteById(used.id()));
    }

    @Test
    @DisplayName("itens são gravados, atualizados e não se repetem para a mesma regra")
    void shouldPersistAndUpdateItems() {
        LegalCase legalCase = persistedCase();
        Document document = persistedDocument(legalCase, "minuta.pdf", "CONTRACT_DRAFT");
        ChecklistRule rule = ruleRepository.findByCaseType(LegalCaseType.CONTRACT_SIGNING).getFirst();
        DocumentChecklistItem missing = DocumentChecklistItem.missing(UUID.randomUUID(), legalCase.id(), rule.id(), NOW);
        itemRepository.saveAll(List.of(missing));
        flushAndClear();

        DocumentChecklistItem satisfied = missing.satisfyWith(document.id(), NOW.plusSeconds(1));
        itemRepository.saveAll(List.of(satisfied));
        flushAndClear();

        assertThat(itemRepository.findByLegalCaseId(legalCase.id())).containsExactly(satisfied);

        DocumentChecklistItem duplicate = DocumentChecklistItem.missing(UUID.randomUUID(), legalCase.id(), rule.id(), NOW);
        assertThatExceptionOfType(RuntimeException.class).isThrownBy(() -> {
            itemRepository.saveAll(List.of(duplicate));
            entityManager.flush();
        });
    }

    @Test
    @DisplayName("o banco recusa item SATISFIED sem documento, mesmo contornando o domínio")
    void shouldEnforceItemConsistency() {
        LegalCase legalCase = persistedCase();
        UUID ruleId = ruleRepository.findByCaseType(LegalCaseType.CONTRACT_SIGNING).getFirst().id();
        flushAndClear();

        assertThatExceptionOfType(RuntimeException.class).isThrownBy(() -> entityManager
                .createNativeQuery("""
                        INSERT INTO document_checklist_items (id, legal_case_id, checklist_rule_id, status)
                        VALUES (?, ?, ?, 'SATISFIED')
                        """)
                .setParameter(1, UUID.randomUUID())
                .setParameter(2, legalCase.id())
                .setParameter(3, ruleId)
                .executeUpdate())
                .withMessageContaining("ck_document_checklist_items_document");
    }

    @Test
    @DisplayName("a listagem de regras é paginada, ordenada e filtrável")
    void shouldPaginateRules() {
        PageResult<ChecklistRule> firstPage = ruleRepository.findAll(null, PageQuery.of(0, 4));
        PageResult<ChecklistRule> contracts = ruleRepository.findAll(LegalCaseType.CONTRACT_SIGNING, PageQuery.of(0, 10));

        assertThat(firstPage.content()).hasSize(4);
        assertThat(firstPage.totalElements()).isGreaterThanOrEqualTo(15);
        assertThat(firstPage.content())
                .extracting(rule -> rule.caseType().name())
                .isSortedAccordingTo(String::compareTo);
        assertThat(contracts.content())
                .allSatisfy(rule -> assertThat(rule.caseType()).isEqualTo(LegalCaseType.CONTRACT_SIGNING))
                .extracting(ChecklistRule::requiredDocumentType)
                .containsExactly("CONTRACT_DRAFT", "FINANCIAL_OPINION", "SIGNATORY_POWERS");
    }

    @Test
    @DisplayName("o tipo de documento vai e volta do banco, e os documentos vêm em ordem estável")
    void shouldPersistDocumentTypeInStableOrder() {
        LegalCase legalCase = persistedCase();
        Document second = persistedDocument(legalCase, "b-parecer.pdf", "FINANCIAL_OPINION");
        Document first = persistedDocument(legalCase, "a-minuta.pdf", "contract_draft");
        Document untyped = persistedDocument(legalCase, "c-anexo.pdf", null);
        flushAndClear();

        assertThat(documentRepository.findByLegalCaseId(legalCase.id()))
                .containsExactly(first, second, untyped)
                .extracting(Document::documentType)
                .containsExactlyElementsOf(Arrays.asList("CONTRACT_DRAFT", "FINANCIAL_OPINION", null));
    }

    private LegalCase persistedCase() {
        LegalCase legalCase = LegalCase.receive(
                UUID.randomUUID(), null, LegalCaseType.CONTRACT_SIGNING, "ana.silva", CasePriority.NORMAL, NOW);
        legalCaseRepository.save(LegalCaseMapper.toEntity(legalCase));
        return legalCase;
    }

    private Document persistedDocument(LegalCase legalCase, String fileName, String documentType) {
        Document document = new Document(
                UUID.randomUUID(),
                legalCase.id(),
                fileName,
                "legal-cases/%s/%s".formatted(legalCase.id(), fileName),
                "application/pdf",
                Sha256Checksum.ofContent(fileName.getBytes()),
                NOW,
                documentType);
        documentRepository.saveAll(List.of(document));
        return document;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
