package com.lexflow.api.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.lexflow.api.AbstractApiIT;
import com.lexflow.api.support.StubLlmClient;
import com.lexflow.application.analysis.LegalAnalysisSchema;
import com.lexflow.domain.ai.AnswerSource;
import com.lexflow.domain.ai.VerificationStatus;
import com.lexflow.domain.ai.QuestionKey;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseType;
import com.lexflow.infrastructure.messaging.LegalCaseReceivedEventListener;
import com.lexflow.infrastructure.messaging.RabbitMqConfiguration;
import com.lexflow.infrastructure.persistence.entity.AiAnalysisResponseEntity;
import com.lexflow.infrastructure.persistence.entity.KnowledgeBaseChunkEntity;
import com.lexflow.infrastructure.persistence.repository.AiAnalysisResponseJpaRepository;
import com.lexflow.infrastructure.persistence.repository.KnowledgeBaseChunkJpaRepository;
import com.lexflow.infrastructure.persistence.repository.KnowledgeBaseSourceJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseAlertJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseStatusHistoryJpaRepository;
import com.lexflow.infrastructure.persistence.repository.PromptVersionJpaRepository;
import com.lexflow.infrastructure.testsupport.DocumentFixtures;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * Critério de aceite do Prompt 13, de ponta a ponta: com uma norma indexada e o LLM simulado, todas
 * as {@code question_key} aplicáveis ao tipo da demanda recebem resposta persistida e a demanda
 * avança para {@code PENDING_HUMAN_REVIEW}.
 *
 * <p>A norma indexada aqui é removida ao fim de cada teste: o banco é compartilhado com os demais
 * testes da API, e uma norma esquecida mudaria o que eles observam.
 *
 * <p>Requer Docker em execução e o Tesseract instalado.
 */
class LegalCaseAnalysisIT extends AbstractApiIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final String ADMIN_USER = "admin";

    private static final String ADMIN_PASSWORD = "lexflow-admin";

    /**
     * Política de teste escrita para cobrir as três perguntas do tipo {@code CONTRACT_SIGNING}: o
     * modelo de embeddings dos testes é lexical, então cada pergunta precisa encontrar na norma as
     * palavras de que trata.
     */
    private static final String ALCADAS =
            """
            Art. 1º A assinatura de contratos de prestação de serviços com valor superior a cem mil reais depende de aprovação prévia do diretor jurídico.

            Art. 2º O contrato de prestação de serviços deve conter objeto, prazo, valor e cláusula de rescisão, e a documentação exigida é a minuta contratual e o parecer financeiro.

            Art. 3º Toda demanda deve estar de acordo com a legislação aplicável e com a política interna da empresa; divergência entre o contrato e a legislação ou a política deve ser submetida ao diretor jurídico.

            Art. 4º A documentação de uma demanda de assinatura de contrato é suficiente quando a minuta contratual e o parecer financeiro estiverem anexados, legíveis e assinados.
            """;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private StubLlmClient llm;

    @Autowired
    private LegalCaseJpaRepository legalCaseRepository;

    @Autowired
    private LegalCaseStatusHistoryJpaRepository statusHistoryRepository;

    @Autowired
    private AiAnalysisResponseJpaRepository responseRepository;

    @Autowired
    private LegalCaseAlertJpaRepository alertRepository;

    @Autowired
    private PromptVersionJpaRepository promptVersionRepository;

    @Autowired
    private KnowledgeBaseSourceJpaRepository sourceRepository;

    @Autowired
    private KnowledgeBaseChunkJpaRepository chunkRepository;

    private final List<UUID> createdSources = new ArrayList<>();

    @BeforeEach
    void startClean() {
        stopListener();
        llm.reset();
        rabbitAdmin.purgeQueue(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMqConfiguration.LEGAL_CASE_RECEIVED_DLQ, false);
    }

    @AfterEach
    void cleanUp() {
        stopListener();
        llm.reset();
        for (UUID sourceId : createdSources) {
            List<KnowledgeBaseChunkEntity> chunks = chunkRepository.findBySourceIdOrderByChunkIndexAsc(sourceId);
            chunkRepository.deleteAll(chunks);
            sourceRepository.deleteById(sourceId);
        }
        createdSources.clear();
    }

    private void stopListener() {
        listenerRegistry.getListenerContainer(LegalCaseReceivedEventListener.LISTENER_ID).stop();
    }

    @Test
    @DisplayName("com a norma indexada, toda pergunta aplicável é respondida e a demanda vai para revisão humana")
    void shouldAnswerEveryApplicableQuestionAndAdvanceToHumanReview() {
        indexPolicy();
        UUID legalCaseId = ingestContract("CONTRACT_DRAFT", "FINANCIAL_OPINION");

        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(statusOf(legalCaseId)).isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW));

        UUID promptVersionId = promptVersionRepository
                .findByPromptKeyAndActiveIsTrue(LegalAnalysisSchema.PROMPT_KEY)
                .orElseThrow()
                .getId();
        List<AiAnalysisResponseEntity> responses = responseRepository.findByLegalCaseId(legalCaseId);

        assertThat(responses)
                .extracting(AiAnalysisResponseEntity::getQuestionKey)
                .containsExactlyInAnyOrderElementsOf(QuestionKey.applicableTo(LegalCaseType.CONTRACT_SIGNING));
        assertThat(responses).allSatisfy(response -> {
            assertThat(response.getAnswerSource())
                    .describedAs("%s -> %s".formatted(response.getQuestionKey(), response.getAnswerText()))
                    .isEqualTo(AnswerSource.LLM);
            assertThat(response.getModelVersion()).isEqualTo(StubLlmClient.MODEL);
            assertThat(response.getPromptVersionId()).isEqualTo(promptVersionId);
            // Toda resposta do modelo cita um trecho que realmente existe na base normativa.
            assertThat(response.getCitedChunks()).isNotEmpty();
            assertThat(chunkRepository.findAllById(response.getCitedChunks())).isNotEmpty();
        });
        assertThat(alertRepository.findByLegalCaseIdOrderByCreatedAtAscIdAsc(legalCaseId)).isEmpty();
        assertThat(statusHistoryRepository.findByLegalCaseIdOrderByChangedAtAsc(legalCaseId))
                .anySatisfy(entry -> assertThat(entry.getReason()).contains("Análise da IA concluída"));
    }

    @Test
    @DisplayName("o prompt leva os fatos, o checklist e os trechos com os identificadores que podem ser citados")
    void shouldBuildThePromptWithFactsChunksAndCitableIds() {
        indexPolicy();
        UUID legalCaseId = ingestContract("CONTRACT_DRAFT", "FINANCIAL_OPINION");
        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(statusOf(legalCaseId)).isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW));

        // A primeira chamada é a extração de fatos; as seguintes são a análise, uma por pergunta.
        List<com.lexflow.application.llm.LlmRequest> analysisRequests = llm.requests().stream()
                .filter(request -> request.outputSchema().contains("cited_chunks"))
                .toList();

        assertThat(analysisRequests).hasSize(QuestionKey.applicableTo(LegalCaseType.CONTRACT_SIGNING).size());
        assertThat(analysisRequests.getFirst().prompt())
                .contains("<fatos>", "<checklist>", "<trechos>")
                .contains("Identificadores de trecho que você pode citar:")
                .contains("Documentação obrigatória completa: sim");
        assertThat(analysisRequests.getFirst().systemPrompt())
                .contains("Você não decide nada")
                .contains("Responda exclusivamente com base");
    }

    @Test
    @DisplayName("com documentação incompleta, a suficiência é respondida por código e não vai ao modelo")
    void shouldAnswerSufficiencyDeterministicallyWhenDocumentationIsIncomplete() {
        indexPolicy();
        UUID legalCaseId = ingestContract("CONTRACT_DRAFT");

        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(statusOf(legalCaseId)).isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW));

        AiAnalysisResponseEntity sufficiency = responseRepository
                .findByLegalCaseIdAndQuestionKey(legalCaseId, QuestionKey.HAS_SUFFICIENT_DOCUMENTATION)
                .orElseThrow();
        assertThat(sufficiency.getAnswerSource()).isEqualTo(AnswerSource.DETERMINISTIC);
        assertThat(sufficiency.getAnswerText()).contains("Documentação incompleta", "FINANCIAL_OPINION");
        assertThat(sufficiency.getModelVersion()).isNull();
        assertThat(sufficiency.getPromptVersionId()).isNull();
        assertThat(llm.requests())
                .filteredOn(request -> request.outputSchema().contains("cited_chunks"))
                .hasSize(2);
    }

    @Test
    @DisplayName("a resposta de uma pergunta crítica passa pela segunda checagem antes de chegar ao revisor")
    void shouldVerifyCriticalAnswersBeforeHumanReview() {
        indexPolicy();
        UUID legalCaseId = ingestContract("CONTRACT_DRAFT", "FINANCIAL_OPINION");

        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(statusOf(legalCaseId)).isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW));

        AiAnalysisResponseEntity critica = responseRepository
                .findByLegalCaseIdAndQuestionKey(legalCaseId, QuestionKey.CAN_SIGN_CONTRACT)
                .orElseThrow();
        assertThat(critica.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(critica.getVerificationNotes()).contains("sustenta a resposta");
        // As perguntas não críticas não gastam uma chamada a mais.
        assertThat(responseRepository
                        .findByLegalCaseIdAndQuestionKey(legalCaseId, QuestionKey.COMPLIES_WITH_LAW_AND_POLICY)
                        .orElseThrow()
                        .getVerificationStatus())
                .isEqualTo(VerificationStatus.NOT_VERIFIED);
    }

    @Test
    @DisplayName("critério de aceite: resposta não sustentada pelos trechos citados vira FAILED, com confiança zerada")
    void shouldFlagAnswerNotSupportedByTheCitedChunks() {
        indexPolicy();
        llm.verificationSupported(false);
        UUID legalCaseId = ingestContract("CONTRACT_DRAFT", "FINANCIAL_OPINION");

        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(statusOf(legalCaseId)).isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW));

        AiAnalysisResponseEntity critica = responseRepository
                .findByLegalCaseIdAndQuestionKey(legalCaseId, QuestionKey.CAN_SIGN_CONTRACT)
                .orElseThrow();
        assertThat(critica.getVerificationStatus()).isEqualTo(VerificationStatus.FAILED);
        assertThat(critica.getConfidenceScore()).isZero();
        assertThat(critica.getVerificationNotes()).contains("outro assunto");
        // A checagem sinaliza; ela não reescreve: o texto original continua lá, e a demanda chega ao
        // revisor humano como qualquer outra.
        assertThat(critica.getAnswerText()).isNotBlank().doesNotContain("outro assunto");
    }

    @Test
    @DisplayName("sem norma indexada, as respostas declaram que a base normativa não tem o assunto")
    void shouldAnswerNotFoundWithoutAnyIndexedSource() {
        UUID legalCaseId = ingestContract("CONTRACT_DRAFT", "FINANCIAL_OPINION");

        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(statusOf(legalCaseId)).isEqualTo(LegalCaseStatus.PENDING_HUMAN_REVIEW));

        assertThat(responseRepository.findByLegalCaseId(legalCaseId)).allSatisfy(response -> {
            assertThat(response.getAnswerSource()).isEqualTo(AnswerSource.DETERMINISTIC);
            assertThat(response.getCitedChunks()).isEmpty();
        });
        assertThat(responseRepository
                        .findByLegalCaseIdAndQuestionKey(legalCaseId, QuestionKey.CAN_SIGN_CONTRACT)
                        .orElseThrow()
                        .getAnswerText())
                .startsWith("informação não encontrada na base normativa");
        // Nenhuma chamada de análise foi feita: sem contexto, não há o que perguntar ao modelo.
        assertThat(llm.requests()).noneSatisfy(request ->
                assertThat(request.outputSchema()).contains("cited_chunks"));
    }

    /** Indexa a política de alçadas usada como base normativa dos testes. */
    private void indexPolicy() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth(ADMIN_USER, ADMIN_PASSWORD)
                .postForEntity(
                        "/api/v1/knowledge-base/sources",
                        jsonEntity("""
                                {"title": "IT Política de Alçadas", "sourceType": "INTERNAL_POLICY", "text": "%s"}
                                """
                                .formatted(ALCADAS.replace("\n", "\\n"))),
                        String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        createdSources.add(UUID.fromString(response.getBody().replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1")));
    }

    /**
     * Envia a demanda de assinatura de contrato e inicia o consumo.
     *
     * <p>Cada arquivo é uma fixture diferente de propósito: o mesmo conteúdo enviado duas vezes seria
     * reconhecido como reenvio do mesmo documento (índice único por checksum) e não satisfaria a
     * segunda regra do checklist.
     *
     * @param documentTypes tipos a anexar, em ordem: o primeiro recebe o contrato de teste e o
     *     segundo, a minuta em DOCX
     */
    private UUID ingestContract(String... documentTypes) {
        String[] fixtures = {"contrato-texto-nativo.pdf", "minuta-aditivo.docx"};
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("caseType", "CONTRACT_SIGNING");
        body.add("requester", "ana.silva");
        for (int index = 0; index < documentTypes.length; index++) {
            String fixture = fixtures[index];
            body.add("files", new ByteArrayResource(DocumentFixtures.read(fixture)) {
                @Override
                public String getFilename() {
                    return fixture;
                }
            });
            body.add("documentTypes", documentTypes[index]);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/legal-cases", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID legalCaseId = UUID.fromString(response.getBody().replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
        listenerRegistry.getListenerContainer(LegalCaseReceivedEventListener.LISTENER_ID).start();
        return legalCaseId;
    }

    private LegalCaseStatus statusOf(UUID legalCaseId) {
        return legalCaseRepository.findById(legalCaseId).orElseThrow().getStatus();
    }

    private static HttpEntity<String> jsonEntity(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
