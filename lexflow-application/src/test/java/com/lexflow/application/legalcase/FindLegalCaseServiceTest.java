package com.lexflow.application.legalcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.application.legalcase.support.FactExtractionTestDoubles.InMemoryLegalCaseAlertRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryDocumentRepository;
import com.lexflow.application.legalcase.support.IngestionTestDoubles.InMemoryLegalCaseRepository;
import com.lexflow.domain.alert.LegalCaseAlert;
import com.lexflow.domain.alert.LegalCaseAlertType;
import com.lexflow.domain.document.Document;
import com.lexflow.domain.document.Sha256Checksum;
import com.lexflow.domain.exception.LegalCaseNotFoundException;
import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FindLegalCaseServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    private InMemoryLegalCaseRepository legalCaseRepository;
    private InMemoryDocumentRepository documentRepository;
    private InMemoryLegalCaseAlertRepository alertRepository;
    private FindLegalCaseService service;

    @BeforeEach
    void setUp() {
        legalCaseRepository = new InMemoryLegalCaseRepository();
        documentRepository = new InMemoryDocumentRepository();
        alertRepository = new InMemoryLegalCaseAlertRepository();
        service = new FindLegalCaseService(legalCaseRepository, documentRepository, alertRepository);
    }

    @Test
    @DisplayName("devolve a demanda com os seus arquivos")
    void shouldFindLegalCaseWithDocuments() {
        UUID legalCaseId = UUID.randomUUID();
        legalCaseRepository.save(LegalCase.receive(
                legalCaseId, "REF-1", LegalCaseType.LAWSUIT_CLOSURE, "ana.silva", CasePriority.LOW, NOW));
        documentRepository.saveAll(List.of(new Document(
                UUID.randomUUID(),
                legalCaseId,
                "sentenca.pdf",
                "legal-cases/%s/documents/1".formatted(legalCaseId),
                "application/pdf",
                Sha256Checksum.ofContent("a".getBytes(StandardCharsets.UTF_8)),
                NOW)));

        alertRepository.save(LegalCaseAlert.open(
                UUID.randomUUID(), legalCaseId, null, LegalCaseAlertType.FACT_EXTRACTION_REFUSED, "recusa", NOW));

        LegalCaseWithDocuments found = service.findById(legalCaseId);

        assertThat(found.legalCase().id()).isEqualTo(legalCaseId);
        assertThat(found.documents()).extracting(Document::fileName).containsExactly("sentenca.pdf");
        assertThat(found.alerts()).extracting(LegalCaseAlert::type)
                .containsExactly(LegalCaseAlertType.FACT_EXTRACTION_REFUSED);
    }

    @Test
    @DisplayName("demanda inexistente resulta em exceção de não encontrada")
    void shouldFailWhenLegalCaseDoesNotExist() {
        UUID unknownId = UUID.randomUUID();

        assertThatExceptionOfType(LegalCaseNotFoundException.class)
                .isThrownBy(() -> service.findById(unknownId))
                .satisfies(e -> assertThat(e.legalCaseId()).isEqualTo(unknownId));
    }
}
