package com.lexflow.domain.checklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.lexflow.domain.exception.ChecklistRuleNotFoundException;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentChecklistTest {

    private static final Instant EVALUATED_AT = Instant.parse("2026-01-10T12:00:00Z");
    private static final UUID LEGAL_CASE_ID = UUID.randomUUID();

    private final ChecklistRule mandatoryRule = new ChecklistRule(
            UUID.randomUUID(), LegalCaseType.CONTRACT_SIGNING, "CONTRACT_DRAFT", "Minuta do contrato", true);
    private final ChecklistRule optionalRule = new ChecklistRule(
            UUID.randomUUID(), LegalCaseType.CONTRACT_SIGNING, "FINANCIAL_OPINION", "Parecer financeiro", false);

    private DocumentChecklistItem itemFor(ChecklistRule rule) {
        return DocumentChecklistItem.pending(UUID.randomUUID(), LEGAL_CASE_ID, rule.id());
    }

    @Test
    @DisplayName("documentação incompleta nunca é considerada suficiente")
    void shouldNotBeSufficientWhileMandatoryItemIsMissing() {
        DocumentChecklistItem mandatoryItem = itemFor(mandatoryRule).markMissing(EVALUATED_AT);
        DocumentChecklistItem optionalItem =
                itemFor(optionalRule).satisfyWith(UUID.randomUUID(), EVALUATED_AT);

        DocumentChecklist checklist =
                new DocumentChecklist(List.of(mandatoryItem, optionalItem), List.of(mandatoryRule, optionalRule));

        assertThat(checklist.hasSufficientDocumentation()).isFalse();
        assertThat(checklist.missingMandatoryItems()).containsExactly(mandatoryItem);
    }

    @Test
    @DisplayName("item obrigatório pendente também impede a suficiência")
    void shouldNotBeSufficientWhileMandatoryItemIsPending() {
        DocumentChecklist checklist = new DocumentChecklist(List.of(itemFor(mandatoryRule)), List.of(mandatoryRule));

        assertThat(checklist.hasSufficientDocumentation()).isFalse();
    }

    @Test
    void shouldBeSufficientWhenEveryMandatoryItemIsSatisfied() {
        DocumentChecklistItem mandatoryItem =
                itemFor(mandatoryRule).satisfyWith(UUID.randomUUID(), EVALUATED_AT);
        DocumentChecklistItem optionalItem = itemFor(optionalRule).markMissing(EVALUATED_AT);

        DocumentChecklist checklist =
                new DocumentChecklist(List.of(mandatoryItem, optionalItem), List.of(mandatoryRule, optionalRule));

        assertThat(checklist.hasSufficientDocumentation()).isTrue();
        assertThat(checklist.missingMandatoryItems()).isEmpty();
        assertThat(checklist.items()).hasSize(2);
        assertThat(checklist.ruleOf(mandatoryItem)).isEqualTo(mandatoryRule);
    }

    @Test
    @DisplayName("um checklist vazio é suficiente: o tipo de demanda não exige documento algum")
    void emptyChecklistShouldBeSufficient() {
        assertThat(new DocumentChecklist(List.of(), List.of()).hasSufficientDocumentation())
                .isTrue();
    }

    @Test
    void shouldRejectItemPointingToUnknownRule() {
        DocumentChecklistItem orphanItem = itemFor(mandatoryRule);

        assertThatExceptionOfType(ChecklistRuleNotFoundException.class)
                .isThrownBy(() -> new DocumentChecklist(List.of(orphanItem), List.of(optionalRule)))
                .satisfies(exception -> assertThat(exception.checklistRuleId()).isEqualTo(mandatoryRule.id()));
    }

    @Test
    void shouldValidateRuleFields() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChecklistRule(
                        UUID.randomUUID(), LegalCaseType.SUPPLIER_HIRING, "  ", "descrição", true))
                .withMessageContaining("requiredDocumentType");
    }
}
