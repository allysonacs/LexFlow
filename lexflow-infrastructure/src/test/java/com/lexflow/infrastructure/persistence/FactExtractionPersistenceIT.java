package com.lexflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.application.fact.ExtractLegalFactsUseCase;
import com.lexflow.application.prompt.PromptTemplate;
import com.lexflow.domain.ai.AiExtractedFact;
import com.lexflow.domain.ai.PromptVersion;
import com.lexflow.domain.alert.LegalCaseAlert;
import com.lexflow.domain.alert.LegalCaseAlertType;
import com.lexflow.domain.document.Document;
import com.lexflow.domain.document.Sha256Checksum;
import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseType;
import com.lexflow.infrastructure.persistence.adapter.AiExtractedFactRepositoryAdapter;
import com.lexflow.infrastructure.persistence.adapter.LegalCaseAlertRepositoryAdapter;
import com.lexflow.infrastructure.persistence.adapter.PromptVersionRepositoryAdapter;
import com.lexflow.infrastructure.persistence.entity.PromptVersionEntity;
import com.lexflow.infrastructure.persistence.mapper.DocumentMapper;
import com.lexflow.infrastructure.persistence.mapper.LegalCaseMapper;
import com.lexflow.infrastructure.persistence.repository.DocumentJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import com.lexflow.infrastructure.persistence.repository.PromptVersionJpaRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Persistência da extração de fatos (Prompt 11) contra um PostgreSQL real: prompt semeado, fatos com
 * versão de prompt, unicidade por documento e alertas.
 */
@Import({
    AiExtractedFactRepositoryAdapter.class,
    LegalCaseAlertRepositoryAdapter.class,
    PromptVersionRepositoryAdapter.class
})
class FactExtractionPersistenceIT extends AbstractPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    @Autowired
    private AiExtractedFactRepositoryAdapter factRepository;

    @Autowired
    private LegalCaseAlertRepositoryAdapter alertRepository;

    @Autowired
    private PromptVersionRepositoryAdapter promptRepository;

    @Autowired
    private PromptVersionJpaRepository promptJpaRepository;

    @Autowired
    private LegalCaseJpaRepository legalCaseRepository;

    @Autowired
    private DocumentJpaRepository documentRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("a migration semeia a versão 1 do prompt, com as seções que o caso de uso preenche")
    void shouldSeedFactExtractionPrompt() {
        PromptVersion version = promptRepository.findActive(ExtractLegalFactsUseCase.PROMPT_KEY).orElseThrow();
        PromptTemplate template = PromptTemplate.of(version);

        assertThat(version.version()).isEqualTo(1);
        assertThat(template.render("SISTEMA", Map.of("CASE_TYPE", "CONTRACT_SIGNING", "SPECIFIC_FIELDS", "- x")))
                .contains("Não deduza")
                .contains("Tipo de demanda: CONTRACT_SIGNING");
        assertThat(template.render("USUARIO", Map.of("FILE_NAME", "a.pdf", "DOCUMENT_TEXT", "texto")))
                .contains("<documento nome=\"a.pdf\">");
        assertThat(template.render("REFORCO", Map.of("VIOLATIONS", "- v"))).contains("- v");
        assertThat(promptRepository.findActive("INEXISTENTE")).isEmpty();
    }

    @Test
    @DisplayName("só uma versão ativa por prompt")
    void shouldAllowSingleActiveVersion() {
        assertThatExceptionOfType(RuntimeException.class).isThrownBy(() -> {
            promptJpaRepository.save(new PromptVersionEntity(
                    UUID.randomUUID(), ExtractLegalFactsUseCase.PROMPT_KEY, 2, "outra", true, NOW));
            entityManager.flush();
        });
    }

    @Test
    @DisplayName("fatos vão e voltam com a versão do prompt, e cada documento tem um registro só")
    void shouldPersistFactsOncePerDocument() {
        LegalCase legalCase = persistedCase();
        Document document = persistedDocument(legalCase);
        UUID promptId = promptRepository.findActive(ExtractLegalFactsUseCase.PROMPT_KEY).orElseThrow().id();
        AiExtractedFact fact = new AiExtractedFact(
                UUID.randomUUID(), legalCase.id(), document.id(), "{\"parties\": []}", "claude-opus-5", promptId, NOW);

        factRepository.save(fact);
        flushAndClear();

        assertThat(factRepository.findByLegalCaseId(legalCase.id())).singleElement().satisfies(reloaded -> {
            assertThat(reloaded.promptVersionId()).isEqualTo(promptId);
            assertThat(reloaded.modelVersion()).isEqualTo("claude-opus-5");
            assertThat(reloaded.documentId()).isEqualTo(document.id());
        });
        assertThatExceptionOfType(RuntimeException.class).isThrownBy(() -> {
            factRepository.save(new AiExtractedFact(
                    UUID.randomUUID(), legalCase.id(), document.id(), "{}", "claude-opus-5", promptId, NOW));
            entityManager.flush();
        });
    }

    @Test
    @DisplayName("fato com versão de prompt inexistente é recusado pelo banco")
    void shouldRequireExistingPromptVersion() {
        LegalCase legalCase = persistedCase();
        Document document = persistedDocument(legalCase);

        assertThatExceptionOfType(RuntimeException.class).isThrownBy(() -> {
            factRepository.save(new AiExtractedFact(
                    UUID.randomUUID(), legalCase.id(), document.id(), "{}", "claude-opus-5", UUID.randomUUID(), NOW));
            entityManager.flush();
        });
    }

    @Test
    @DisplayName("alertas vão e voltam em ordem de criação, abertos ou resolvidos")
    void shouldPersistAlerts() {
        LegalCase legalCase = persistedCase();
        Document document = persistedDocument(legalCase);
        LegalCaseAlert open = LegalCaseAlert.open(
                UUID.randomUUID(), legalCase.id(), document.id(),
                LegalCaseAlertType.FACT_EXTRACTION_INVALID_OUTPUT, "Saída inválida", NOW);
        LegalCaseAlert resolved = new LegalCaseAlert(
                UUID.randomUUID(), legalCase.id(), null, LegalCaseAlertType.FACT_EXTRACTION_REFUSED,
                "Recusa tratada", NOW.plusSeconds(1), NOW.plusSeconds(60));

        alertRepository.save(resolved);
        alertRepository.save(open);
        flushAndClear();

        assertThat(alertRepository.findByLegalCaseId(legalCase.id())).containsExactly(open, resolved);
        assertThat(alertRepository.findByLegalCaseId(UUID.randomUUID())).isEmpty();
    }

    private LegalCase persistedCase() {
        LegalCase legalCase = LegalCase.receive(
                UUID.randomUUID(), null, LegalCaseType.CONTRACT_SIGNING, "ana.silva", CasePriority.NORMAL, NOW);
        legalCaseRepository.save(LegalCaseMapper.toEntity(legalCase));
        return legalCase;
    }

    private Document persistedDocument(LegalCase legalCase) {
        Document document = new Document(
                UUID.randomUUID(), legalCase.id(), "contrato.pdf", "legal-cases/x/contrato.pdf", "application/pdf",
                Sha256Checksum.ofContent(legalCase.id().toString().getBytes()), NOW);
        documentRepository.save(DocumentMapper.toEntity(document));
        return document;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
