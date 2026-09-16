package com.lexflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.domain.ai.AiAnalysisResponse;
import com.lexflow.domain.ai.AiExtractedFact;
import com.lexflow.domain.ai.ConfidenceScore;
import com.lexflow.domain.ai.QuestionKey;
import com.lexflow.domain.checklist.ChecklistItemStatus;
import com.lexflow.domain.checklist.ChecklistRule;
import com.lexflow.domain.checklist.DocumentChecklistItem;
import com.lexflow.domain.decision.Decision;
import com.lexflow.domain.decision.DecisionType;
import com.lexflow.domain.document.Document;
import com.lexflow.domain.document.Sha256Checksum;
import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseType;
import com.lexflow.infrastructure.persistence.entity.LegalCaseStatusHistoryEntity;
import com.lexflow.infrastructure.persistence.mapper.AiAnalysisResponseMapper;
import com.lexflow.infrastructure.persistence.mapper.AiExtractedFactMapper;
import com.lexflow.infrastructure.persistence.mapper.ChecklistRuleMapper;
import com.lexflow.infrastructure.persistence.mapper.DecisionMapper;
import com.lexflow.infrastructure.persistence.mapper.DocumentChecklistItemMapper;
import com.lexflow.infrastructure.persistence.mapper.DocumentMapper;
import com.lexflow.infrastructure.persistence.mapper.LegalCaseMapper;
import com.lexflow.infrastructure.persistence.repository.AiAnalysisResponseJpaRepository;
import com.lexflow.infrastructure.persistence.repository.AiExtractedFactJpaRepository;
import com.lexflow.infrastructure.persistence.repository.ChecklistRuleJpaRepository;
import com.lexflow.infrastructure.persistence.repository.DecisionJpaRepository;
import com.lexflow.infrastructure.persistence.repository.DocumentChecklistItemJpaRepository;
import com.lexflow.infrastructure.persistence.repository.DocumentJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseStatusHistoryJpaRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * Verifica, contra um PostgreSQL real, que uma demanda completa vai e volta do banco sem perder nada,
 * inclusive as colunas {@code jsonb}.
 */
class LegalCasePersistenceIT extends AbstractPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-02-01T10:15:30Z");

    @Autowired
    private LegalCaseJpaRepository legalCaseRepository;

    @Autowired
    private LegalCaseStatusHistoryJpaRepository statusHistoryRepository;

    @Autowired
    private DocumentJpaRepository documentRepository;

    @Autowired
    private ChecklistRuleJpaRepository checklistRuleRepository;

    @Autowired
    private DocumentChecklistItemJpaRepository checklistItemRepository;

    @Autowired
    private AiExtractedFactJpaRepository extractedFactRepository;

    @Autowired
    private AiAnalysisResponseJpaRepository analysisResponseRepository;

    @Autowired
    private DecisionJpaRepository decisionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("uma demanda completa é gravada e recuperada sem perda de dados")
    void shouldPersistAndRetrieveACompleteLegalCase() {
        LegalCase legalCase = LegalCase.receive(
                UUID.randomUUID(),
                "REQ-2026-0042",
                LegalCaseType.CONTRACT_SIGNING,
                "compras@empresa.com",
                "Minuta de contrato de licenciamento de software",
                CasePriority.HIGH,
                NOW);

        legalCaseRepository.save(LegalCaseMapper.toEntity(legalCase));
        flushAndClear();

        LegalCase reloaded = LegalCaseMapper.toDomain(
                legalCaseRepository.findById(legalCase.id()).orElseThrow());

        assertThat(reloaded).isEqualTo(legalCase);
    }

    @Test
    @DisplayName("a transição de status é gravada no histórico")
    void shouldPersistStatusHistory() {
        LegalCase legalCase = persistedCase();
        LegalCase classifying = legalCase.transitionTo(LegalCaseStatus.CLASSIFYING, NOW.plus(1, ChronoUnit.MINUTES));

        legalCaseRepository.save(LegalCaseMapper.toEntity(classifying));
        statusHistoryRepository.save(new LegalCaseStatusHistoryEntity(
                UUID.randomUUID(),
                legalCase.id(),
                legalCase.status(),
                classifying.status(),
                classifying.updatedAt(),
                "SYSTEM",
                "Evento de ingestão consumido"));
        flushAndClear();

        List<LegalCaseStatusHistoryEntity> history =
                statusHistoryRepository.findByLegalCaseIdOrderByChangedAtAsc(legalCase.id());

        assertThat(history).hasSize(1);
        assertThat(history.getFirst().getPreviousStatus()).isEqualTo(LegalCaseStatus.RECEIVED);
        assertThat(history.getFirst().getNewStatus()).isEqualTo(LegalCaseStatus.CLASSIFYING);
        assertThat(legalCaseRepository
                        .findById(legalCase.id())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(LegalCaseStatus.CLASSIFYING);
    }

    @Test
    @DisplayName("documento, regra e item de checklist preservam o vínculo entre si")
    void shouldPersistDocumentsAndChecklist() {
        LegalCase legalCase = persistedCase();
        Document document = new Document(
                UUID.randomUUID(),
                legalCase.id(),
                "minuta-contrato.pdf",
                "legal-cases/%s/minuta-contrato.pdf".formatted(legalCase.id()),
                "application/pdf",
                Sha256Checksum.of("a".repeat(64)),
                NOW,
                "EXECUTED_CONTRACT_COPY");
        // Código fora do seed (V5), para não colidir com o índice único de (case_type, required_document_type).
        ChecklistRule rule = new ChecklistRule(
                UUID.randomUUID(),
                LegalCaseType.CONTRACT_SIGNING,
                "EXECUTED_CONTRACT_COPY",
                "Minuta do contrato assinada pelas partes",
                true);
        DocumentChecklistItem item = DocumentChecklistItem.pending(UUID.randomUUID(), legalCase.id(), rule.id())
                .satisfyWith(document.id(), NOW);

        documentRepository.save(DocumentMapper.toEntity(document));
        checklistRuleRepository.save(ChecklistRuleMapper.toEntity(rule));
        checklistItemRepository.save(DocumentChecklistItemMapper.toEntity(item));
        flushAndClear();

        assertThat(DocumentMapper.toDomain(
                        documentRepository.findById(document.id()).orElseThrow()))
                .isEqualTo(document);
        assertThat(ChecklistRuleMapper.toDomain(
                        checklistRuleRepository.findById(rule.id()).orElseThrow()))
                .isEqualTo(rule);

        DocumentChecklistItem reloadedItem = DocumentChecklistItemMapper.toDomain(
                checklistItemRepository.findByLegalCaseId(legalCase.id()).getFirst());
        assertThat(reloadedItem).isEqualTo(item);
        assertThat(reloadedItem.status()).isEqualTo(ChecklistItemStatus.SATISFIED);
        assertThat(documentRepository.findByLegalCaseIdAndChecksumSha256(legalCase.id(), "a".repeat(64)))
                .isPresent();
    }

    @Test
    @DisplayName("as colunas jsonb sobrevivem à ida e volta do banco")
    void shouldPreserveJsonbColumns() {
        LegalCase legalCase = persistedCase();
        Document document = new Document(
                UUID.randomUUID(),
                legalCase.id(),
                "contrato.pdf",
                "legal-cases/%s/contrato.pdf".formatted(legalCase.id()),
                "application/pdf",
                Sha256Checksum.of("b".repeat(64)),
                NOW);
        documentRepository.save(DocumentMapper.toEntity(document));

        String extractedJson =
                "{\"parties\":[\"ACME LTDA\",\"Fornecedor XPTO\"],\"amount\":150000.5,\"signedAt\":null}";
        AiExtractedFact fact = new AiExtractedFact(
                UUID.randomUUID(), legalCase.id(), document.id(), extractedJson, "claude-opus-5", NOW);
        List<UUID> citedChunks = List.of(UUID.randomUUID(), UUID.randomUUID());
        AiAnalysisResponse response = AiAnalysisResponse.of(
                UUID.randomUUID(),
                legalCase.id(),
                QuestionKey.CAN_SIGN_CONTRACT,
                "O contrato pode ser assinado, observada a alçada do diretor.",
                ConfidenceScore.of(0.87),
                citedChunks,
                "claude-opus-5",
                null,
                NOW);

        extractedFactRepository.save(AiExtractedFactMapper.toEntity(fact));
        analysisResponseRepository.save(AiAnalysisResponseMapper.toEntity(response));
        flushAndClear();

        AiExtractedFact reloadedFact = AiExtractedFactMapper.toDomain(
                extractedFactRepository.findById(fact.id()).orElseThrow());
        AiAnalysisResponse reloadedResponse = AiAnalysisResponseMapper.toDomain(analysisResponseRepository
                .findByLegalCaseIdAndQuestionKey(legalCase.id(), QuestionKey.CAN_SIGN_CONTRACT)
                .orElseThrow());

        // O tipo jsonb guarda a estrutura do JSON, não o texto: ele reordena chaves e normaliza
        // espaços. Por isso a comparação é semântica, sobre a árvore, e não sobre a string.
        assertThat(readTree(reloadedFact.extractedJson())).isEqualTo(readTree(extractedJson));
        assertThat(readTree(reloadedFact.extractedJson()).get("signedAt").isNull())
                .isTrue();
        assertThat(reloadedResponse.citedChunks()).containsExactlyElementsOf(citedChunks);
        assertThat(reloadedResponse.confidenceScore().value()).isEqualTo(0.87);
        assertThat(reloadedResponse.answerText()).isEqualTo(response.answerText());
    }

    @Test
    @DisplayName("a decisão humana é gravada e vinculada à demanda")
    void shouldPersistDecision() {
        LegalCase legalCase = persistedCase();
        Decision decision = new Decision(
                UUID.randomUUID(),
                legalCase.id(),
                DecisionType.RETURNED_FOR_CORRECTION,
                "revisor@empresa.com",
                NOW,
                "Falta o parecer financeiro.");

        decisionRepository.save(DecisionMapper.toEntity(decision));
        flushAndClear();

        assertThat(DecisionMapper.toDomain(
                        decisionRepository.findByLegalCaseIdOrderByDecidedAtAsc(legalCase.id())
                                .getFirst()))
                .isEqualTo(decision);
    }

    @Test
    @DisplayName("a listagem por status é paginada")
    void shouldListByStatusWithPagination() {
        persistedCase();
        persistedCase();
        flushAndClear();

        var page = legalCaseRepository.findByStatus(LegalCaseStatus.RECEIVED, PageRequest.of(0, 1));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(2);
    }

    private LegalCase persistedCase() {
        LegalCase legalCase = LegalCase.receive(
                UUID.randomUUID(),
                "REQ-" + UUID.randomUUID(),
                LegalCaseType.CONTRACT_SIGNING,
                "compras@empresa.com",
                CasePriority.NORMAL,
                NOW);
        legalCaseRepository.save(LegalCaseMapper.toEntity(legalCase));
        return legalCase;
    }

    /** Lê o JSON como árvore, para comparar estrutura em vez de texto. */
    private JsonNode readTree(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("JSON inválido devolvido pelo banco: " + json, e);
        }
    }

    /** Garante que o que foi lido veio mesmo do banco, e não do cache de primeiro nível. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
