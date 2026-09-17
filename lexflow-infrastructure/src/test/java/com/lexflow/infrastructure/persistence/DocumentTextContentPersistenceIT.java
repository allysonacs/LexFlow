package com.lexflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.domain.document.Document;
import com.lexflow.domain.document.DocumentTextContent;
import com.lexflow.domain.document.Sha256Checksum;
import com.lexflow.domain.document.TextExtractionMethod;
import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseType;
import com.lexflow.infrastructure.persistence.adapter.DocumentTextContentRepositoryAdapter;
import com.lexflow.infrastructure.persistence.mapper.DocumentMapper;
import com.lexflow.infrastructure.persistence.mapper.LegalCaseMapper;
import com.lexflow.infrastructure.persistence.repository.DocumentJpaRepository;
import com.lexflow.infrastructure.persistence.repository.DocumentTextContentJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Verifica a tabela {@code document_text_contents} (Prompt 08) contra um PostgreSQL real: ida e volta
 * pelo adapter, unicidade por documento e as restrições que espelham as regras do domínio.
 */
@Import(DocumentTextContentRepositoryAdapter.class)
class DocumentTextContentPersistenceIT extends AbstractPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    @Autowired
    private DocumentTextContentRepositoryAdapter adapter;

    @Autowired
    private DocumentTextContentJpaRepository jpaRepository;

    @Autowired
    private LegalCaseJpaRepository legalCaseRepository;

    @Autowired
    private DocumentJpaRepository documentRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("texto extraído, sem texto e falha vão e voltam do banco sem perda")
    void shouldRoundTripEveryStatus() {
        LegalCase legalCase = persistedCase();
        Document pdf = persistedDocument(legalCase, "contrato.pdf");
        Document png = persistedDocument(legalCase, "em-branco.png");
        Document broken = persistedDocument(legalCase, "corrompido.pdf");
        String longText = "Cláusula primeira — ação, indenização e vigência. ".repeat(2_000);
        List<DocumentTextContent> contents = List.of(
                DocumentTextContent.extracted(UUID.randomUUID(), pdf, longText, TextExtractionMethod.NATIVE_TEXT, NOW),
                DocumentTextContent.extracted(UUID.randomUUID(), png, "  ", TextExtractionMethod.OCR, NOW),
                DocumentTextContent.failed(UUID.randomUUID(), broken, "PDF inválido", NOW));

        contents.forEach(adapter::save);
        flushAndClear();

        assertThat(adapter.findByLegalCaseId(legalCase.id())).containsExactlyInAnyOrderElementsOf(contents);
        assertThat(adapter.findByLegalCaseId(UUID.randomUUID())).isEmpty();
        assertThat(jpaRepository.findByDocumentId(pdf.id())).get()
                .satisfies(entity -> assertThat(entity.getContent()).hasSize(longText.strip().length()));
    }

    @Test
    @DisplayName("um documento não pode ter dois registros de texto")
    void shouldRejectSecondTextForSameDocument() {
        LegalCase legalCase = persistedCase();
        Document pdf = persistedDocument(legalCase, "contrato.pdf");
        adapter.save(DocumentTextContent.extracted(UUID.randomUUID(), pdf, "a", TextExtractionMethod.NATIVE_TEXT, NOW));
        flushAndClear();

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> {
            adapter.save(DocumentTextContent.extracted(UUID.randomUUID(), pdf, "b", TextExtractionMethod.OCR, NOW));
            jpaRepository.flush();
        });
    }

    @Test
    @DisplayName("o banco recusa uma falha com texto, mesmo que a gravação contorne o domínio")
    void shouldEnforceConsistencyInDatabase() {
        LegalCase legalCase = persistedCase();
        Document pdf = persistedDocument(legalCase, "contrato.pdf");
        flushAndClear();

        assertThatExceptionOfType(RuntimeException.class).isThrownBy(() -> entityManager
                .createNativeQuery("""
                        INSERT INTO document_text_contents
                            (id, document_id, legal_case_id, content, extraction_method, status, failure_reason, extracted_at)
                        VALUES (?, ?, ?, 'texto', NULL, 'FAILED', 'erro', now())
                        """)
                .setParameter(1, UUID.randomUUID())
                .setParameter(2, pdf.id())
                .setParameter(3, legalCase.id())
                .executeUpdate())
                .withMessageContaining("ck_document_text_contents_consistency");
    }

    private LegalCase persistedCase() {
        LegalCase legalCase = LegalCase.receive(
                UUID.randomUUID(), null, LegalCaseType.CONTRACT_SIGNING, "compras@empresa.com", CasePriority.NORMAL, NOW);
        legalCaseRepository.save(LegalCaseMapper.toEntity(legalCase));
        return legalCase;
    }

    private Document persistedDocument(LegalCase legalCase, String fileName) {
        Document document = new Document(
                UUID.randomUUID(),
                legalCase.id(),
                fileName,
                "legal-cases/%s/%s".formatted(legalCase.id(), fileName),
                "application/pdf",
                Sha256Checksum.ofContent(fileName.getBytes()),
                NOW);
        documentRepository.save(DocumentMapper.toEntity(document));
        return document;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
