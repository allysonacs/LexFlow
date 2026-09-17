package com.lexflow.application.checklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.application.legalcase.support.ChecklistTestDoubles;
import com.lexflow.application.legalcase.support.ChecklistTestDoubles.InMemoryChecklistRuleRepository;
import com.lexflow.application.legalcase.support.ChecklistTestDoubles.InMemoryDocumentChecklistItemRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryDocumentRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryLegalCaseRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.SequentialIdGenerator;
import com.lexflow.domain.checklist.ChecklistItemStatus;
import com.lexflow.domain.checklist.ChecklistRule;
import com.lexflow.domain.checklist.DocumentChecklist;
import com.lexflow.domain.checklist.DocumentChecklistItem;
import com.lexflow.domain.document.Document;
import com.lexflow.domain.document.Sha256Checksum;
import com.lexflow.domain.exception.LegalCaseNotFoundException;
import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseStatusTransitionRules;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DocumentChecklistServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    private InMemoryLegalCaseRepository legalCaseRepository;
    private InMemoryDocumentRepository documentRepository;
    private InMemoryChecklistRuleRepository ruleRepository;
    private InMemoryDocumentChecklistItemRepository itemRepository;
    private DocumentChecklistService service;

    @BeforeEach
    void setUp() {
        legalCaseRepository = new InMemoryLegalCaseRepository();
        documentRepository = new InMemoryDocumentRepository();
        itemRepository = new InMemoryDocumentChecklistItemRepository();
        ruleRepository = new InMemoryChecklistRuleRepository().referencedBy(itemRepository);
        ChecklistTestDoubles.seedRules().forEach(ruleRepository::save);
        service = new DocumentChecklistService(
                legalCaseRepository,
                documentRepository,
                ruleRepository,
                itemRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SequentialIdGenerator());
    }

    private LegalCase givenLegalCase(LegalCaseType type, LegalCaseStatus status) {
        LegalCase legalCase = LegalCase.receive(UUID.randomUUID(), null, type, "ana.silva", CasePriority.NORMAL, NOW);
        LegalCaseStatusTransitionRules anyTransition = new LegalCaseStatusTransitionRules() {
            @Override
            public boolean isAllowed(LegalCaseStatus current, LegalCaseStatus target) {
                return true;
            }
        };
        if (status != LegalCaseStatus.RECEIVED) {
            legalCase = legalCase.transitionTo(status, NOW, anyTransition);
        }
        return legalCaseRepository.save(legalCase);
    }

    private Document givenDocument(LegalCase legalCase, String fileName, String documentType) {
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

    @ParameterizedTest(name = "{0}")
    @EnumSource(LegalCaseType.class)
    @DisplayName("ao classificar, cada tipo recebe um item MISSING por regra configurada para ele")
    void shouldGenerateItemsForEachCaseType(LegalCaseType type) {
        LegalCase legalCase = givenLegalCase(type, LegalCaseStatus.EXTRACTING);
        List<ChecklistRule> rulesOfType = ruleRepository.findByCaseType(type);

        DocumentChecklist checklist = service.synchronize(legalCase);

        assertThat(rulesOfType).hasSize(3);
        assertThat(checklist.items())
                .hasSize(3)
                .allSatisfy(item -> assertThat(item.status()).isEqualTo(ChecklistItemStatus.MISSING))
                .extracting(DocumentChecklistItem::checklistRuleId)
                .containsExactlyInAnyOrderElementsOf(rulesOfType.stream().map(ChecklistRule::id).toList());
        assertThat(itemRepository.findByLegalCaseId(legalCase.id())).hasSize(3);
        assertThat(checklist.hasSufficientDocumentation()).isFalse();
        assertThat(checklist.missingMandatoryDocumentTypes()).hasSize(2);
    }

    @Test
    @DisplayName("documentos enviados com o tipo exigido tornam os itens SATISFIED e a documentação suficiente")
    void shouldLinkDocumentsAndAnswerSufficient() {
        LegalCase legalCase = givenLegalCase(LegalCaseType.CONTRACT_SIGNING, LegalCaseStatus.EXTRACTING);
        Document draft = givenDocument(legalCase, "minuta.pdf", "CONTRACT_DRAFT");
        givenDocument(legalCase, "parecer.pdf", "FINANCIAL_OPINION");

        service.synchronize(legalCase);
        LegalCaseChecklist view = service.findByLegalCaseId(legalCase.id());

        assertThat(view.evaluated()).isTrue();
        assertThat(view.hasSufficientDocumentation()).isTrue();
        assertThat(view.checklist().items())
                .extracting(item -> view.checklist().ruleOf(item).requiredDocumentType())
                // Obrigatórios primeiro, depois pelo código.
                .containsExactly("CONTRACT_DRAFT", "FINANCIAL_OPINION", "SIGNATORY_POWERS");
        assertThat(view.checklist().items().get(0)).satisfies(item -> {
            assertThat(item.status()).isEqualTo(ChecklistItemStatus.SATISFIED);
            assertThat(item.documentId()).isEqualTo(draft.id());
            assertThat(item.evaluatedAt()).isEqualTo(NOW);
        });
        assertThat(view.checklist().items().get(2).status()).isEqualTo(ChecklistItemStatus.MISSING);
    }

    @Test
    @DisplayName("faltando um obrigatório, a documentação não é suficiente — mesmo com o opcional presente")
    void shouldAnswerInsufficientWhenMandatoryIsMissing() {
        LegalCase legalCase = givenLegalCase(LegalCaseType.CONTRACT_SIGNING, LegalCaseStatus.EXTRACTING);
        givenDocument(legalCase, "minuta.pdf", "CONTRACT_DRAFT");
        givenDocument(legalCase, "procuracao.pdf", "SIGNATORY_POWERS");
        givenDocument(legalCase, "sem-tipo.pdf", null);

        service.synchronize(legalCase);
        LegalCaseChecklist view = service.findByLegalCaseId(legalCase.id());

        assertThat(view.hasSufficientDocumentation()).isFalse();
        assertThat(view.checklist().missingMandatoryDocumentTypes()).containsExactly("FINANCIAL_OPINION");
    }

    @Test
    @DisplayName("avançar o pipeline não torna suficiente uma documentação incompleta")
    void shouldStayInsufficientEvenIfPipelineAdvances() {
        LegalCase legalCase = givenLegalCase(LegalCaseType.SETTLEMENT_PAYMENT, LegalCaseStatus.EXTRACTING);
        givenDocument(legalCase, "acordo.pdf", "SETTLEMENT_AGREEMENT");
        service.synchronize(legalCase);

        for (LegalCaseStatus later : List.of(
                LegalCaseStatus.AI_ANALYSIS_IN_PROGRESS,
                LegalCaseStatus.PENDING_HUMAN_REVIEW,
                LegalCaseStatus.APPROVED,
                LegalCaseStatus.CLOSED)) {
            legalCaseRepository.save(new LegalCase(
                    legalCase.id(), null, legalCase.caseType(), later, legalCase.requester(), null,
                    legalCase.priority(), NOW, NOW));
            assertThat(service.findByLegalCaseId(legalCase.id()).hasSufficientDocumentation())
                    .as("status %s", later)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("antes da classificação o checklist não foi avaliado e a resposta é sempre negativa")
    void shouldNotBeSufficientBeforeClassification() {
        LegalCase legalCase = givenLegalCase(LegalCaseType.CONTRACT_SIGNING, LegalCaseStatus.RECEIVED);

        LegalCaseChecklist view = service.findByLegalCaseId(legalCase.id());

        assertThat(view.evaluated()).isFalse();
        assertThat(view.checklist().items()).isEmpty();
        // Um checklist vazio é "suficiente" para o domínio; sem avaliação, a consulta não pode dizer isso.
        assertThat(view.checklist().hasSufficientDocumentation()).isTrue();
        assertThat(view.hasSufficientDocumentation()).isFalse();
    }

    @Test
    @DisplayName("tipo sem nenhuma regra configurada tem checklist vazio e suficiente depois de avaliado")
    void shouldBeSufficientWhenTypeRequiresNothing() {
        ruleRepository.findByCaseType(LegalCaseType.LAWSUIT_CLOSURE).forEach(rule -> ruleRepository.deleteById(rule.id()));
        LegalCase legalCase = givenLegalCase(LegalCaseType.LAWSUIT_CLOSURE, LegalCaseStatus.EXTRACTING);

        assertThat(service.synchronize(legalCase).items()).isEmpty();
        assertThat(service.findByLegalCaseId(legalCase.id()).hasSufficientDocumentation()).isTrue();
        assertThat(itemRepository.saveCalls()).isEmpty();
    }

    @Test
    @DisplayName("sincronizar de novo não duplica itens e só regrava o que mudou")
    void shouldResynchronizeWithoutDuplicates() {
        LegalCase legalCase = givenLegalCase(LegalCaseType.CONTRACT_SIGNING, LegalCaseStatus.EXTRACTING);
        service.synchronize(legalCase);
        service.synchronize(legalCase);
        assertThat(itemRepository.saveCalls()).hasSize(1);

        Document opinion = givenDocument(legalCase, "parecer.pdf", "FINANCIAL_OPINION");
        service.synchronize(legalCase);

        assertThat(itemRepository.findByLegalCaseId(legalCase.id())).hasSize(3);
        assertThat(itemRepository.saveCalls()).hasSize(2);
        assertThat(itemRepository.saveCalls().get(1)).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(ChecklistItemStatus.SATISFIED);
            assertThat(item.documentId()).isEqualTo(opinion.id());
        });
    }

    @Test
    @DisplayName("regra removida do tipo depois da geração continua valendo para o item que já existe")
    void shouldKeepItemsOfRulesNoLongerInTheType() {
        LegalCase legalCase = givenLegalCase(LegalCaseType.CONTRACT_SIGNING, LegalCaseStatus.EXTRACTING);
        service.synchronize(legalCase);
        // Simula uma regra que deixou de ser encontrada pelo tipo, mas ainda existe pelo id.
        InMemoryChecklistRuleRepository narrowed = new InMemoryChecklistRuleRepository() {
            @Override
            public List<ChecklistRule> findByCaseType(LegalCaseType caseType) {
                return List.of();
            }
        };
        ruleRepository.findByCaseType(LegalCaseType.CONTRACT_SIGNING).forEach(narrowed::save);
        DocumentChecklistService narrowedService = new DocumentChecklistService(
                legalCaseRepository, documentRepository, narrowed, itemRepository,
                Clock.fixed(NOW, ZoneOffset.UTC), new SequentialIdGenerator());

        assertThat(narrowedService.synchronize(legalCase).items()).hasSize(3);
    }

    @Test
    @DisplayName("demanda inexistente resulta em LegalCaseNotFoundException")
    void shouldReportMissingLegalCase() {
        assertThatExceptionOfType(LegalCaseNotFoundException.class)
                .isThrownBy(() -> service.findByLegalCaseId(UUID.randomUUID()));
    }
}
