package com.lexflow.api.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.lexflow.api.AbstractApiIT;
import com.lexflow.domain.document.TextExtractionMethod;
import com.lexflow.domain.document.TextExtractionStatus;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.infrastructure.messaging.LegalCaseReceivedEventListener;
import com.lexflow.infrastructure.messaging.RabbitMqConfiguration;
import com.lexflow.infrastructure.persistence.entity.DocumentTextContentEntity;
import com.lexflow.infrastructure.persistence.entity.LegalCaseStatusHistoryEntity;
import com.lexflow.infrastructure.persistence.repository.DocumentJpaRepository;
import com.lexflow.infrastructure.persistence.repository.DocumentTextContentJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseStatusHistoryJpaRepository;
import com.lexflow.infrastructure.testsupport.DocumentFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
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
 * Critério de aceite do Prompt 08, de ponta a ponta: documentos de teste entram pela API e saem
 * classificados e com o texto extraído, sem nenhuma intervenção manual. A classificação e a extração
 * de texto não usam LLM; a etapa seguinte, de fatos, usa o LLM simulado dos testes.
 *
 * <p>Atravessa HTTP, MinIO, RabbitMQ, PostgreSQL, Tika e Tesseract. Requer Docker em execução e o
 * Tesseract instalado com o idioma {@code por} (ver {@code TikaDocumentTextExtractorTest}).
 */
class LegalCaseClassificationExtractionIT extends AbstractApiIT {

    /** O OCR é a etapa lenta; o limite folgado evita falso negativo em máquina carregada. */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private LegalCaseJpaRepository legalCaseRepository;

    @Autowired
    private DocumentJpaRepository documentRepository;

    @Autowired
    private LegalCaseStatusHistoryJpaRepository statusHistoryRepository;

    @Autowired
    private DocumentTextContentJpaRepository textContentRepository;

    @BeforeEach
    void startWithEmptyQueue() {
        stopListener();
        rabbitAdmin.purgeQueue(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_DLQ, false);
    }

    @AfterEach
    void stopConsumer() {
        stopListener();
    }

    private void stopListener() {
        listenerRegistry.getListenerContainer(LegalCaseReceivedEventListener.LISTENER_ID).stop();
    }

    /** Envia a demanda pela API com as fixtures informadas e espera o consumo terminar. */
    private UUID ingestAndProcess(String caseType, String description, Map<String, byte[]> files) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("caseType", caseType);
        body.add("requester", "ana.silva");
        if (description != null) {
            body.add("description", description);
        }
        files.forEach((name, content) -> body.add("files", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return name;
            }
        }));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/legal-cases", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID legalCaseId = UUID.fromString(response.getBody().replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));

        listenerRegistry.getListenerContainer(LegalCaseReceivedEventListener.LISTENER_ID).start();
        await().atMost(TIMEOUT).untilAsserted(() -> {
            // Com o LLM simulado respondendo bem, o pipeline segue até a análise da IA.
            assertThat(legalCaseRepository.findById(legalCaseId).orElseThrow().getStatus())
                    .isEqualTo(LegalCaseStatus.AI_ANALYSIS_IN_PROGRESS);
            assertThat(textContentRepository.findByLegalCaseId(legalCaseId)).hasSize(files.size());
        });
        assertThat(rabbitAdmin.getQueueInfo(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_DLQ).getMessageCount())
                .isZero();
        return legalCaseId;
    }

    /** Texto extraído indexado pelo nome do arquivo de origem. */
    private Map<String, DocumentTextContentEntity> textsByFileName(UUID legalCaseId) {
        Map<UUID, String> names = documentRepository.findByLegalCaseId(legalCaseId).stream()
                .collect(Collectors.toMap(document -> document.getId(), document -> document.getFileName()));
        return textContentRepository.findByLegalCaseId(legalCaseId).stream()
                .collect(Collectors.toMap(text -> names.get(text.getDocumentId()), Function.identity()));
    }

    /** Motivo gravado na transição {@code CLASSIFYING → EXTRACTING}. */
    private String classificationReason(UUID legalCaseId) {
        List<LegalCaseStatusHistoryEntity> history =
                statusHistoryRepository.findByLegalCaseIdOrderByChangedAtAsc(legalCaseId);
        return history.stream()
                .filter(entry -> entry.getNewStatus() == LegalCaseStatus.EXTRACTING)
                .findFirst()
                .orElseThrow()
                .getReason();
    }

    private static String normalized(DocumentTextContentEntity text) {
        return text.getContent().replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("contrato em PDF nativo e aditivo em DOCX: classificação confirmada e texto lido sem OCR")
    void shouldClassifyContractAndExtractNativeText() {
        UUID legalCaseId = ingestAndProcess("CONTRACT_SIGNING", "Assinatura de contrato com fornecedora", Map.of(
                "contrato-prestacao-servicos.pdf", DocumentFixtures.read("contrato-texto-nativo.pdf"),
                "minuta-termo-aditivo.docx", DocumentFixtures.read("minuta-aditivo.docx")));

        assertThat(classificationReason(legalCaseId)).contains("CONTRACT_SIGNING informado e confirmado");

        Map<String, DocumentTextContentEntity> texts = textsByFileName(legalCaseId);
        assertThat(texts.get("contrato-prestacao-servicos.pdf")).satisfies(text -> {
            assertThat(text.getStatus()).isEqualTo(TextExtractionStatus.EXTRACTED);
            assertThat(text.getExtractionMethod()).isEqualTo(TextExtractionMethod.NATIVE_TEXT);
            assertThat(normalized(text)).contains("CONTRATO DE PRESTAÇÃO DE SERVIÇOS").contains("R$ 15.000,00");
        });
        assertThat(texts.get("minuta-termo-aditivo.docx")).satisfies(text -> {
            assertThat(text.getExtractionMethod()).isEqualTo(TextExtractionMethod.NATIVE_TEXT);
            assertThat(normalized(text)).contains("MINUTA DO TERMO ADITIVO");
        });
    }

    @Test
    @DisplayName("termo de acordo em imagem: classificado como pagamento de acordo e lido por OCR")
    void shouldClassifySettlementAndExtractTextFromImage() {
        UUID legalCaseId = ingestAndProcess("SETTLEMENT_PAYMENT", "Pagamento de acordo trabalhista", Map.of(
                "termo-de-acordo.png", DocumentFixtures.read("termo-de-acordo.png")));

        assertThat(classificationReason(legalCaseId)).contains("SETTLEMENT_PAYMENT informado e confirmado");
        assertThat(textsByFileName(legalCaseId).get("termo-de-acordo.png")).satisfies(text -> {
            assertThat(text.getStatus()).isEqualTo(TextExtractionStatus.EXTRACTED);
            assertThat(text.getExtractionMethod()).isEqualTo(TextExtractionMethod.OCR);
            assertThat(normalized(text)).contains("Joana Pereira").contains("R$ 8.500,00");
        });
    }

    @Test
    @DisplayName("petição digitalizada: classificada como encerramento e lida por OCR, página a página")
    void shouldClassifyLawsuitClosureAndExtractTextFromScannedPdf() {
        UUID legalCaseId = ingestAndProcess("LAWSUIT_CLOSURE", null, Map.of(
                "peticao-encerramento.pdf", DocumentFixtures.read("peticao-digitalizada.pdf")));

        assertThat(classificationReason(legalCaseId)).contains("LAWSUIT_CLOSURE informado e confirmado");
        assertThat(textsByFileName(legalCaseId).get("peticao-encerramento.pdf")).satisfies(text -> {
            assertThat(text.getExtractionMethod()).isEqualTo(TextExtractionMethod.OCR);
            assertThat(normalized(text)).contains("0001234-56.2024.8.26.0100");
        });
    }

    @Test
    @DisplayName("tipo informado que contradiz os documentos é mantido, mas a divergência fica no histórico")
    void shouldKeepDeclaredTypeAndRecordDivergence() {
        UUID legalCaseId = ingestAndProcess("SUPPLIER_HIRING", null, Map.of(
                "proposta-comercial.jpg", DocumentFixtures.read("proposta-comercial.jpg")));

        assertThat(legalCaseRepository.findById(legalCaseId).orElseThrow().getCaseType().name())
                .isEqualTo("SUPPLIER_HIRING");
        assertThat(classificationReason(legalCaseId))
                .contains("SUPPLIER_HIRING informado, mas as palavras-chave apontam outro tipo")
                .contains("PROPOSAL_ACCEPTANCE");
        assertThat(normalized(textsByFileName(legalCaseId).get("proposta-comercial.jpg")))
                .contains("PROPOSTA COMERCIAL");
    }

    @Test
    @DisplayName("arquivo corrompido é registrado como falho, sem travar a demanda nem ir para a dead-letter")
    void shouldRecordUnreadableDocumentWithoutBlockingTheCase() {
        UUID legalCaseId = ingestAndProcess("CONTRACT_SIGNING", null, Map.of(
                "contrato.pdf", DocumentFixtures.read("contrato-texto-nativo.pdf"),
                "corrompido.pdf", "isto não é um pdf".getBytes(StandardCharsets.UTF_8)));

        Map<String, DocumentTextContentEntity> texts = textsByFileName(legalCaseId);
        assertThat(texts.get("contrato.pdf").getStatus()).isEqualTo(TextExtractionStatus.EXTRACTED);
        assertThat(texts.get("corrompido.pdf")).satisfies(text -> {
            assertThat(text.getStatus()).isEqualTo(TextExtractionStatus.FAILED);
            assertThat(text.getContent()).isNull();
            assertThat(text.getFailureReason()).contains("PDF");
        });
    }
}
