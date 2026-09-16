package com.lexflow.api.legalcase;

import static org.assertj.core.api.Assertions.assertThat;

import com.lexflow.api.AbstractApiIT;
import com.lexflow.application.document.DocumentStoragePort;
import com.lexflow.infrastructure.persistence.repository.DocumentJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseStatusHistoryJpaRepository;
import com.lexflow.infrastructure.persistence.repository.ProcessingEventJpaRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Testes de integração da API de ingestão (Prompt 05), com a aplicação inteira no ar e um PostgreSQL
 * real via Testcontainers.
 *
 * <p>São de ponta a ponta de propósito: idempotência e validação de entrada só ficam provadas quando
 * a requisição atravessa HTTP, multipart, caso de uso e banco — em um teste de unidade o índice único
 * de {@code processing_events}, que é o que realmente impede a duplicação, nem apareceria.
 *
 * <p>Requer Docker em execução.
 */
class LegalCaseIngestionIT extends AbstractApiIT {

    private static final String BASE_PATH = "/api/v1/legal-cases";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private LegalCaseJpaRepository legalCaseRepository;

    @Autowired
    private DocumentJpaRepository documentRepository;

    @Autowired
    private LegalCaseStatusHistoryJpaRepository statusHistoryRepository;

    @Autowired
    private ProcessingEventJpaRepository processingEventRepository;

    @Autowired
    private DocumentStoragePort documentStorage;

    /** Monta um arquivo em memória com o nome que o servidor deve enxergar na parte multipart. */
    private static ByteArrayResource file(String fileName, String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    private static MultiValueMap<String, Object> form(String caseType, ByteArrayResource... files) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("caseType", caseType);
        body.add("requester", "ana.silva");
        body.add("priority", "HIGH");
        body.add("externalReference", "REF-" + UUID.randomUUID());
        for (ByteArrayResource file : files) {
            body.add("files", file);
        }
        return body;
    }

    private ResponseEntity<String> post(MultiValueMap<String, Object> body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (idempotencyKey != null) {
            headers.set(LegalCaseController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        return restTemplate.exchange(BASE_PATH, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static UUID idOf(ResponseEntity<String> response) {
        return UUID.fromString(response.getBody().replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }

    @Test
    @DisplayName("POST cria a demanda em RECEIVED, grava os documentos e devolve a URL de consulta")
    void shouldCreateLegalCase() {
        ResponseEntity<String> response =
                post(form("CONTRACT_SIGNING", file("contrato.pdf", "conteúdo do contrato")), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"status\":\"RECEIVED\"").contains("\"documentCount\":1");

        UUID legalCaseId = idOf(response);
        assertThat(response.getHeaders().getLocation()).hasToString(BASE_PATH + "/" + legalCaseId);
        assertThat(response.getBody()).contains("\"statusUrl\":\"%s/%s\"".formatted(BASE_PATH, legalCaseId));

        assertThat(legalCaseRepository.findById(legalCaseId)).isPresent();
        assertThat(documentRepository.findByLegalCaseId(legalCaseId)).singleElement().satisfies(document -> {
            assertThat(document.getFileName()).isEqualTo("contrato.pdf");
            // O tipo canônico do formato, e não o "application/octet-stream" enviado pelo cliente.
            assertThat(document.getMimeType()).isEqualTo("application/pdf");
            assertThat(document.getChecksumSha256()).hasSize(64);
            // O binário chegou ao storage e volta de lá idêntico ao que foi enviado (Prompt 06).
            assertThat(document.getStoragePath())
                    .isEqualTo("legal-cases/%s/%s.pdf".formatted(legalCaseId, document.getChecksumSha256()));
            assertThat(documentStorage.retrieve(document.getStoragePath()))
                    .asString(StandardCharsets.UTF_8)
                    .isEqualTo("conteúdo do contrato");
        });
        // Nem o estado inicial fica de fora da linha do tempo (seção 4 da base de conhecimento).
        assertThat(statusHistoryRepository.findByLegalCaseIdOrderByChangedAtAsc(legalCaseId))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getPreviousStatus()).isNull());
    }

    @Test
    @DisplayName("GET devolve o status atual e os metadados básicos da demanda")
    void shouldReturnLegalCaseStatus() {
        UUID legalCaseId = idOf(post(form("SUPPLIER_HIRING", file("proposta.pdf", "proposta")), null));

        ResponseEntity<String> response = restTemplate.getForEntity(BASE_PATH + "/" + legalCaseId, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"status\":\"RECEIVED\"")
                .contains("\"caseType\":\"SUPPLIER_HIRING\"")
                .contains("\"priority\":\"HIGH\"")
                .contains("\"requester\":\"ana.silva\"")
                .contains("proposta.pdf")
                // O caminho no storage é detalhe interno e não pode vazar no contrato da API.
                .doesNotContain("storagePath");
    }

    @Test
    @DisplayName("reenvio com a mesma Idempotency-Key devolve a mesma demanda, sem duplicar")
    void shouldNotDuplicateOnSameIdempotencyKey() {
        String idempotencyKey = "ingestao-" + UUID.randomUUID();
        long casesBefore = legalCaseRepository.count();

        ResponseEntity<String> first = post(form("SETTLEMENT_PAYMENT", file("acordo.pdf", "acordo")), idempotencyKey);
        ResponseEntity<String> second = post(form("SETTLEMENT_PAYMENT", file("acordo.pdf", "acordo")), idempotencyKey);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(idOf(second)).isEqualTo(idOf(first));

        assertThat(legalCaseRepository.count()).isEqualTo(casesBefore + 1);
        assertThat(documentRepository.findByLegalCaseId(idOf(first))).hasSize(1);
        assertThat(statusHistoryRepository.findByLegalCaseIdOrderByChangedAtAsc(idOf(first)))
                .hasSize(1);
        assertThat(processingEventRepository.existsByIdempotencyKey("legal-case-ingestion:" + idempotencyKey))
                .isTrue();
    }

    @Test
    @DisplayName("requisições sem Idempotency-Key criam demandas independentes")
    void shouldCreateDistinctCasesWithoutIdempotencyKey() {
        UUID first = idOf(post(form("PROPOSAL_ACCEPTANCE", file("proposta.pdf", "a")), null));
        UUID second = idOf(post(form("PROPOSAL_ACCEPTANCE", file("proposta.pdf", "a")), null));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("caseType inválido resulta em 400 no formato padronizado de erro")
    void shouldRejectInvalidCaseType() {
        long casesBefore = legalCaseRepository.count();

        ResponseEntity<String> response = post(form("CONTRATO_QUALQUER", file("contrato.pdf", "a")), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .contains("\"code\":\"INVALID_REQUEST\"")
                .contains("Tipo de demanda inválido")
                .contains("\"timestamp\"")
                .contains("\"path\":\"%s\"".formatted(BASE_PATH));
        assertThat(legalCaseRepository.count()).isEqualTo(casesBefore);
    }

    @Test
    @DisplayName("arquivo em formato não aceito resulta em 400 e não cria demanda")
    void shouldRejectUnsupportedFileFormat() {
        long casesBefore = legalCaseRepository.count();

        ResponseEntity<String> response = post(form("CONTRACT_SIGNING", file("planilha.xlsx", "a")), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Formato de arquivo não aceito").contains("pdf");
        assertThat(legalCaseRepository.count()).isEqualTo(casesBefore);
    }

    @Test
    @DisplayName("requisição sem arquivo resulta em 400")
    void shouldRejectRequestWithoutFiles() {
        ResponseEntity<String> response = post(form("CONTRACT_SIGNING"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"code\":\"INVALID_REQUEST\"");
    }

    @Test
    @DisplayName("consulta de demanda inexistente resulta em 404")
    void shouldReturnNotFoundForUnknownLegalCase() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(BASE_PATH + "/" + UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"code\":\"RESOURCE_NOT_FOUND\"");
    }
}
