package com.lexflow.application.checklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.lexflow.application.legalcase.support.ChecklistTestDoubles.InMemoryChecklistRuleRepository;
import com.lexflow.application.legalcase.support.ChecklistTestDoubles.InMemoryDocumentChecklistItemRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.DirectTransactionRunner;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.SequentialIdGenerator;
import com.lexflow.application.pagination.PageQuery;
import com.lexflow.application.pagination.PageResult;
import com.lexflow.domain.checklist.ChecklistRule;
import com.lexflow.domain.checklist.DocumentChecklistItem;
import com.lexflow.domain.exception.ChecklistRuleNotFoundException;
import com.lexflow.domain.exception.InvalidDocumentTypeException;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ManageChecklistRulesServiceTest {

    private InMemoryChecklistRuleRepository ruleRepository;
    private InMemoryDocumentChecklistItemRepository itemRepository;
    private DirectTransactionRunner transactionRunner;
    private ManageChecklistRulesService service;

    @BeforeEach
    void setUp() {
        itemRepository = new InMemoryDocumentChecklistItemRepository();
        ruleRepository = new InMemoryChecklistRuleRepository().referencedBy(itemRepository);
        transactionRunner = new DirectTransactionRunner();
        service = new ManageChecklistRulesService(
                ruleRepository, itemRepository, transactionRunner, new SequentialIdGenerator());
    }

    private ChecklistRule givenRule(LegalCaseType type, String documentType) {
        return service.create(type, new ChecklistRuleCommand(documentType, "descrição", true));
    }

    private void givenRuleInUse(ChecklistRule rule) {
        itemRepository.saveAll(List.of(DocumentChecklistItem.missing(
                UUID.randomUUID(), UUID.randomUUID(), rule.id(), Instant.parse("2026-03-10T12:00:00Z"))));
    }

    @Test
    @DisplayName("cria a regra com o código normalizado, em uma transação")
    void shouldCreateRule() {
        ChecklistRule rule = service.create(
                LegalCaseType.CONTRACT_SIGNING, new ChecklistRuleCommand("contract_draft", " Minuta ", true));

        assertThat(rule.requiredDocumentType()).isEqualTo("CONTRACT_DRAFT");
        assertThat(rule.description()).isEqualTo("Minuta");
        assertThat(rule.mandatory()).isTrue();
        assertThat(service.findById(rule.id())).isEqualTo(rule);
        assertThat(transactionRunner.transactionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("o mesmo documento não pode ser exigido duas vezes pelo mesmo tipo")
    void shouldRejectDuplicate() {
        givenRule(LegalCaseType.CONTRACT_SIGNING, "CONTRACT_DRAFT");

        assertThatExceptionOfType(DuplicateChecklistRuleException.class)
                .isThrownBy(() -> givenRule(LegalCaseType.CONTRACT_SIGNING, "contract_draft"))
                .withMessageContaining("CONTRACT_DRAFT");
        // Em outro tipo de demanda, o mesmo documento é outra regra.
        assertThat(givenRule(LegalCaseType.PROPOSAL_ACCEPTANCE, "CONTRACT_DRAFT")).isNotNull();
    }

    @Test
    @DisplayName("dados inválidos são recusados antes de gravar")
    void shouldValidateInput() {
        assertThatExceptionOfType(InvalidDocumentTypeException.class)
                .isThrownBy(() -> givenRule(LegalCaseType.CONTRACT_SIGNING, "minuta do contrato"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChecklistRuleCommand("CONTRACT_DRAFT", null, null))
                .withMessageContaining("mandatory");
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> service.create(null, new ChecklistRuleCommand("A", null, true)));
        assertThat(ruleRepository.count()).isZero();
    }

    @Test
    @DisplayName("descrição e obrigatoriedade podem mudar mesmo com a regra em uso")
    void shouldUpdateDescriptionAndMandatoryOfRuleInUse() {
        ChecklistRule rule = givenRule(LegalCaseType.CONTRACT_SIGNING, "CONTRACT_DRAFT");
        givenRuleInUse(rule);

        ChecklistRule updated = service.update(rule.id(), new ChecklistRuleCommand("CONTRACT_DRAFT", "Nova", false));

        assertThat(updated.description()).isEqualTo("Nova");
        assertThat(updated.mandatory()).isFalse();
        assertThat(service.findById(rule.id()).mandatory()).isFalse();
    }

    @Test
    @DisplayName("o documento exigido só muda enquanto a regra não está em uso, e sem duplicar outra regra")
    void shouldGuardRequiredDocumentChange() {
        ChecklistRule rule = givenRule(LegalCaseType.CONTRACT_SIGNING, "CONTRACT_DRAFT");
        givenRule(LegalCaseType.CONTRACT_SIGNING, "FINANCIAL_OPINION");

        assertThat(service.update(rule.id(), new ChecklistRuleCommand("SIGNED_CONTRACT", null, true))
                        .requiredDocumentType())
                .isEqualTo("SIGNED_CONTRACT");
        assertThatExceptionOfType(DuplicateChecklistRuleException.class)
                .isThrownBy(() -> service.update(rule.id(), new ChecklistRuleCommand("FINANCIAL_OPINION", null, true)));

        givenRuleInUse(rule);
        assertThatExceptionOfType(ChecklistRuleInUseException.class)
                .isThrownBy(() -> service.update(rule.id(), new ChecklistRuleCommand("CONTRACT_DRAFT", null, true)))
                .withMessageContaining("opcional");
    }

    @Test
    @DisplayName("exclui regra sem uso e recusa a exclusão de regra em uso")
    void shouldDeleteOnlyUnusedRules() {
        ChecklistRule unused = givenRule(LegalCaseType.CONTRACT_SIGNING, "CONTRACT_DRAFT");
        ChecklistRule used = givenRule(LegalCaseType.CONTRACT_SIGNING, "FINANCIAL_OPINION");
        givenRuleInUse(used);

        service.delete(unused.id());

        assertThat(ruleRepository.findById(unused.id())).isEmpty();
        assertThatExceptionOfType(ChecklistRuleInUseException.class).isThrownBy(() -> service.delete(used.id()));
        assertThat(ruleRepository.findById(used.id())).isPresent();
    }

    @Test
    @DisplayName("regra inexistente resulta em ChecklistRuleNotFoundException")
    void shouldReportMissingRule() {
        UUID unknown = UUID.randomUUID();

        assertThatExceptionOfType(ChecklistRuleNotFoundException.class).isThrownBy(() -> service.findById(unknown));
        assertThatExceptionOfType(ChecklistRuleNotFoundException.class)
                .isThrownBy(() -> service.update(unknown, new ChecklistRuleCommand("A", null, true)));
        assertThatExceptionOfType(ChecklistRuleNotFoundException.class).isThrownBy(() -> service.delete(unknown));
    }

    @Test
    @DisplayName("a listagem é paginada e pode ser filtrada por tipo")
    void shouldListRulesPaginated() {
        givenRule(LegalCaseType.CONTRACT_SIGNING, "CONTRACT_DRAFT");
        givenRule(LegalCaseType.CONTRACT_SIGNING, "FINANCIAL_OPINION");
        givenRule(LegalCaseType.SUPPLIER_HIRING, "SUPPLIER_CNPJ_CARD");

        PageResult<ChecklistRule> firstPage = service.list(null, PageQuery.of(0, 2));
        PageResult<ChecklistRule> filtered = service.list(LegalCaseType.CONTRACT_SIGNING, PageQuery.of(0, 10));

        assertThat(firstPage.content()).hasSize(2);
        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(filtered.content())
                .extracting(ChecklistRule::requiredDocumentType)
                .containsExactly("CONTRACT_DRAFT", "FINANCIAL_OPINION");
    }
}
