package com.lexflow.domain.checklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.lexflow.domain.document.Document;
import com.lexflow.domain.document.Sha256Checksum;
import com.lexflow.domain.exception.ChecklistRuleNotFoundException;
import com.lexflow.domain.exception.InvalidDocumentTypeException;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
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

    // ---- Geração e vínculo (Prompt 09) ----

    private static final Instant LATER = EVALUATED_AT.plusSeconds(60);

    private final ChecklistRule otherTypeRule = new ChecklistRule(
            UUID.randomUUID(), LegalCaseType.SUPPLIER_HIRING, "SUPPLIER_CNPJ_CARD", "Cartão CNPJ", true);

    private static Supplier<UUID> sequentialIds() {
        AtomicInteger next = new AtomicInteger();
        return () -> UUID.fromString("00000000-0000-0000-0000-%012d".formatted(next.incrementAndGet()));
    }

    private static Document document(String fileName, String documentType) {
        return new Document(
                UUID.randomUUID(),
                LEGAL_CASE_ID,
                fileName,
                "legal-cases/" + fileName,
                "application/pdf",
                Sha256Checksum.ofContent(fileName.getBytes()),
                EVALUATED_AT,
                documentType);
    }

    private DocumentChecklist synchronize(List<DocumentChecklistItem> existing, List<Document> documents, Instant at) {
        return DocumentChecklist.synchronize(
                LEGAL_CASE_ID,
                LegalCaseType.CONTRACT_SIGNING,
                existing,
                List.of(mandatoryRule, optionalRule, otherTypeRule),
                documents,
                sequentialIds(),
                at);
    }

    @Test
    @DisplayName("a geração cria um item MISSING para cada regra do tipo, e só do tipo")
    void shouldGenerateMissingItemsForRulesOfTheCaseType() {
        DocumentChecklist checklist = synchronize(List.of(), List.of(), EVALUATED_AT);

        assertThat(checklist.items()).hasSize(2).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo(ChecklistItemStatus.MISSING);
            assertThat(item.legalCaseId()).isEqualTo(LEGAL_CASE_ID);
            assertThat(item.documentId()).isNull();
            assertThat(item.evaluatedAt()).isEqualTo(EVALUATED_AT);
        });
        assertThat(checklist.items())
                .extracting(DocumentChecklistItem::checklistRuleId)
                .containsExactly(mandatoryRule.id(), optionalRule.id());
        assertThat(checklist.hasSufficientDocumentation()).isFalse();
        assertThat(checklist.missingMandatoryDocumentTypes()).containsExactly("CONTRACT_DRAFT");
        assertThat(checklist.mandatoryItemCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("documento do tipo exigido satisfaz o item, e com todos os obrigatórios a documentação é suficiente")
    void shouldLinkDocumentsByType() {
        Document draft = document("minuta.pdf", "CONTRACT_DRAFT");
        Document untyped = document("anexo.pdf", null);

        DocumentChecklist checklist = synchronize(List.of(), List.of(untyped, draft), EVALUATED_AT);

        DocumentChecklistItem mandatoryItem = checklist.items().get(0);
        assertThat(mandatoryItem.status()).isEqualTo(ChecklistItemStatus.SATISFIED);
        assertThat(mandatoryItem.documentId()).isEqualTo(draft.id());
        assertThat(checklist.items().get(1).status()).isEqualTo(ChecklistItemStatus.MISSING);
        assertThat(checklist.hasSufficientDocumentation()).isTrue();
        assertThat(checklist.missingMandatoryDocumentTypes()).isEmpty();
    }

    @Test
    @DisplayName("com dois documentos do mesmo tipo, vale o primeiro na ordem informada")
    void shouldLinkFirstMatchingDocument() {
        Document first = document("minuta-v1.pdf", "CONTRACT_DRAFT");
        Document second = document("minuta-v2.pdf", "CONTRACT_DRAFT");

        DocumentChecklist checklist = synchronize(List.of(), List.of(first, second), EVALUATED_AT);

        assertThat(checklist.items().get(0).documentId()).isEqualTo(first.id());
    }

    @Test
    @DisplayName("sincronizar de novo não duplica itens e preserva a avaliação de quem não mudou")
    void shouldBeIdempotent() {
        Document draft = document("minuta.pdf", "CONTRACT_DRAFT");
        DocumentChecklist first = synchronize(List.of(), List.of(draft), EVALUATED_AT);

        DocumentChecklist second = synchronize(first.items(), List.of(draft), LATER);

        assertThat(second.items()).containsExactlyElementsOf(first.items());
    }

    @Test
    @DisplayName("documento que chega depois satisfaz o item; documento que some o devolve a MISSING")
    void shouldReevaluateWhenDocumentsChange() {
        DocumentChecklist initial = synchronize(List.of(), List.of(), EVALUATED_AT);
        Document opinion = document("parecer.pdf", "FINANCIAL_OPINION");

        DocumentChecklist withOpinion = synchronize(initial.items(), List.of(opinion), LATER);
        assertThat(withOpinion.items().get(1)).satisfies(item -> {
            assertThat(item.status()).isEqualTo(ChecklistItemStatus.SATISFIED);
            assertThat(item.documentId()).isEqualTo(opinion.id());
            assertThat(item.evaluatedAt()).isEqualTo(LATER);
            assertThat(item.id()).isEqualTo(initial.items().get(1).id());
        });

        DocumentChecklist withoutOpinion = synchronize(withOpinion.items(), List.of(), LATER.plusSeconds(1));
        assertThat(withoutOpinion.items().get(1).status()).isEqualTo(ChecklistItemStatus.MISSING);
        assertThat(withoutOpinion.items().get(1).documentId()).isNull();
    }

    @Test
    @DisplayName("regra criada depois da geração ganha item na próxima sincronização; item PENDING é avaliado")
    void shouldAddItemsForNewRulesAndEvaluatePending() {
        DocumentChecklistItem pending = DocumentChecklistItem.pending(UUID.randomUUID(), LEGAL_CASE_ID, mandatoryRule.id());

        DocumentChecklist checklist = synchronize(List.of(pending), List.of(), EVALUATED_AT);

        assertThat(checklist.items()).hasSize(2);
        assertThat(checklist.items().get(0).id()).isEqualTo(pending.id());
        assertThat(checklist.items().get(0).status()).isEqualTo(ChecklistItemStatus.MISSING);
    }

    @Test
    @DisplayName("itens e documentos de outra demanda são recusados")
    void shouldRejectForeignElements() {
        DocumentChecklistItem foreignItem = DocumentChecklistItem.pending(UUID.randomUUID(), UUID.randomUUID(), mandatoryRule.id());
        Document foreignDocument = new Document(
                UUID.randomUUID(), UUID.randomUUID(), "x.pdf", "p", "application/pdf",
                Sha256Checksum.ofContent(new byte[] {2}), EVALUATED_AT, "CONTRACT_DRAFT");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> synchronize(List.of(foreignItem), List.of(), EVALUATED_AT))
                .withMessageContaining("item");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> synchronize(List.of(), List.of(foreignDocument), EVALUATED_AT))
                .withMessageContaining("documento");
    }

    @Test
    @DisplayName("a regra normaliza o código, edita só os campos permitidos e reconhece o documento que a atende")
    void shouldNormalizeAndEditRule() {
        ChecklistRule rule = new ChecklistRule(
                UUID.randomUUID(), LegalCaseType.CONTRACT_SIGNING, " contract_draft ", "  ", true);

        assertThat(rule.requiredDocumentType()).isEqualTo("CONTRACT_DRAFT");
        assertThat(rule.description()).isNull();
        assertThat(rule.isSatisfiedBy(document("a.pdf", "contract_draft"))).isTrue();
        assertThat(rule.isSatisfiedBy(document("b.pdf", null))).isFalse();

        ChecklistRule changed = rule.withChanges("financial_opinion", " Parecer ", false);
        assertThat(changed.id()).isEqualTo(rule.id());
        assertThat(changed.caseType()).isEqualTo(LegalCaseType.CONTRACT_SIGNING);
        assertThat(changed.requiredDocumentType()).isEqualTo("FINANCIAL_OPINION");
        assertThat(changed.description()).isEqualTo("Parecer");
        assertThat(changed.mandatory()).isFalse();

        assertThatExceptionOfType(InvalidDocumentTypeException.class)
                .isThrownBy(() -> rule.withChanges("minuta do contrato", null, true));
    }
}
