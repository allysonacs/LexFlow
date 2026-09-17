package com.lexflow.application.verification;

import com.lexflow.application.analysis.AiAnalysisResponseRepository;
import com.lexflow.application.analysis.AiAnalysisVerifier;
import com.lexflow.application.analysis.VerificationSummary;
import com.lexflow.application.knowledge.KnowledgeBaseRetriever;
import com.lexflow.application.knowledge.RetrievedChunk;
import com.lexflow.application.llm.LlmClientPort;
import com.lexflow.application.llm.LlmRefusalException;
import com.lexflow.application.llm.LlmRequest;
import com.lexflow.application.llm.LlmResponse;
import com.lexflow.application.llm.LlmResponseValidationException;
import com.lexflow.application.llm.StructuredOutputValidator;
import com.lexflow.application.prompt.PromptTemplate;
import com.lexflow.application.prompt.PromptVersionNotFoundException;
import com.lexflow.application.prompt.PromptVersionRepository;
import com.lexflow.domain.ai.AiAnalysisResponse;
import com.lexflow.domain.ai.QuestionKey;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Segunda checagem das respostas críticas: uma chamada adicional confere se a resposta é mesmo
 * sustentada pelos trechos que ela citou (seção 10, item 4; Prompt 14).
 *
 * <p><strong>Ela confere, não reescreve.</strong> O texto da resposta original nunca é alterado. O
 * que muda é o selo: {@code VERIFIED} quando o texto citado sustenta a resposta, {@code FAILED}
 * quando não — e, aí, a confiança é zerada, para que a resposta chegue ao revisor humano já
 * sinalizada como merecedora de atenção redobrada.
 *
 * <p><strong>O que é verificado.</strong> Só as respostas de perguntas críticas que vieram do modelo
 * e citaram algum trecho. Ficam de fora:
 *
 * <ul>
 *   <li>as respostas determinísticas, cujo fundamento é uma regra de código que o revisor confere
 *       sozinho;
 *   <li>as que declaram que a informação não está na base normativa: não há citação a conferir;
 *   <li>as perguntas não críticas, por decisão de custo — cada verificação é uma chamada a mais.
 * </ul>
 *
 * <p><strong>Se a própria verificação falhar.</strong> Uma saída fora do formato ou uma recusa do
 * modelo deixam a resposta como {@code NOT_VERIFIED}, com o motivo registrado. A checagem é uma
 * camada extra: ela não pode impedir a demanda de chegar ao revisor, e "não verificada" é uma
 * informação honesta. Provedor indisponível, esse sim, sobe como exceção — a mensagem volta para a
 * fila e a verificação é tentada de novo.
 */
public class VerifyAiAnalysisResponseUseCase implements AiAnalysisVerifier {

    static final String SYSTEM_SECTION = "SISTEMA";
    static final String USER_SECTION = "USUARIO";

    private final AiAnalysisResponseRepository responseRepository;
    private final KnowledgeBaseRetriever retriever;
    private final PromptVersionRepository promptVersionRepository;
    private final LlmClientPort llmClient;
    private final StructuredOutputValidator validator;
    private final AnswerVerificationReader verificationReader;
    private final Set<QuestionKey> criticalQuestions;

    /**
     * @param criticalQuestions perguntas submetidas à segunda checagem; a base de conhecimento define
     *     o conjunto mínimo (seção 10, item 4), e a configuração pode ampliá-lo
     */
    public VerifyAiAnalysisResponseUseCase(
            AiAnalysisResponseRepository responseRepository,
            KnowledgeBaseRetriever retriever,
            PromptVersionRepository promptVersionRepository,
            LlmClientPort llmClient,
            StructuredOutputValidator validator,
            AnswerVerificationReader verificationReader,
            Set<QuestionKey> criticalQuestions) {
        this.responseRepository = Objects.requireNonNull(responseRepository, "responseRepository não pode ser nulo");
        this.retriever = Objects.requireNonNull(retriever, "retriever não pode ser nulo");
        this.promptVersionRepository =
                Objects.requireNonNull(promptVersionRepository, "promptVersionRepository não pode ser nulo");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient não pode ser nulo");
        this.validator = Objects.requireNonNull(validator, "validator não pode ser nulo");
        this.verificationReader =
                Objects.requireNonNull(verificationReader, "verificationReader não pode ser nulo");
        this.criticalQuestions = Set.copyOf(
                Objects.requireNonNull(criticalQuestions, "criticalQuestions não pode ser nulo"));
    }

    /** Perguntas submetidas à segunda checagem nesta instalação. */
    public Set<QuestionKey> criticalQuestions() {
        return criticalQuestions;
    }

    @Override
    public VerificationSummary verify(UUID legalCaseId) {
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        List<AiAnalysisResponse> pending = responseRepository.findByLegalCaseId(legalCaseId).stream()
                .filter(response -> criticalQuestions.contains(response.questionKey()))
                .filter(AiAnalysisResponse::awaitsVerification)
                .filter(AiAnalysisResponse::isFromLlm)
                .filter(response -> !response.citedChunks().isEmpty())
                .toList();
        if (pending.isEmpty()) {
            return VerificationSummary.NONE;
        }

        PromptTemplate template = PromptTemplate.of(promptVersionRepository
                .findActive(AnswerVerificationSchema.PROMPT_KEY)
                .orElseThrow(() -> new PromptVersionNotFoundException(AnswerVerificationSchema.PROMPT_KEY)));

        int verified = 0;
        int failed = 0;
        int inconclusive = 0;
        int llmCalls = 0;
        for (AiAnalysisResponse response : pending) {
            List<RetrievedChunk> chunks = retriever.findCited(response.citedChunks());
            if (chunks.isEmpty()) {
                // Os trechos citados sumiram da base (reindexação): não há o que conferir.
                responseRepository.save(response.markVerificationFailed(
                        "Os trechos citados não estão mais na base normativa: a fundamentação não pôde ser conferida."));
                failed++;
                continue;
            }

            llmCalls++;
            Outcome outcome = check(response, chunks, template);
            switch (outcome.result()) {
                case SUPPORTED -> {
                    responseRepository.save(response.markVerified(outcome.notes()));
                    verified++;
                }
                case UNSUPPORTED -> {
                    responseRepository.save(response.markVerificationFailed(outcome.notes()));
                    failed++;
                }
                case INCONCLUSIVE -> inconclusive++;
            }
        }
        return new VerificationSummary(verified, failed, inconclusive, llmCalls);
    }

    /** Uma chamada de verificação, com a validação do schema somada à leitura do veredito. */
    private Outcome check(AiAnalysisResponse response, List<RetrievedChunk> chunks, PromptTemplate template) {
        Map<String, String> values = Map.of(
                "QUESTION_KEY", response.questionKey().name(),
                "QUESTION_TEXT", response.questionKey().statement(),
                "ANSWER", response.answerText(),
                "CHUNKS", renderChunks(chunks));
        LlmRequest request = new LlmRequest(
                template.render(SYSTEM_SECTION, values),
                template.render(USER_SECTION, values),
                AnswerVerificationSchema.schema(),
                null,
                null,
                null,
                null);

        try {
            LlmResponse llmResponse = llmClient.complete(request);
            if (llmResponse.structuredOutput() == null
                    || !validator.violations(AnswerVerificationSchema.schema(), llmResponse.structuredOutput())
                            .isEmpty()) {
                return Outcome.inconclusive();
            }
            AnswerVerification verification = verificationReader.read(llmResponse.structuredOutput());
            String notes = "%s (verificado por %s)".formatted(verification.justification(), llmResponse.model());
            return verification.supported() ? Outcome.supported(notes) : Outcome.unsupported(notes);
        } catch (LlmResponseValidationException | LlmRefusalException e) {
            // A checagem é uma camada extra: se ela falha, a resposta segue sem o selo.
            return Outcome.inconclusive();
        }
    }

    /** Trechos citados, cada um com o seu identificador e a sua fonte. */
    private static String renderChunks(List<RetrievedChunk> chunks) {
        StringBuilder rendered = new StringBuilder();
        for (RetrievedChunk chunk : chunks) {
            rendered.append('[')
                    .append(chunk.chunkId())
                    .append("] ")
                    .append(chunk.citation())
                    .append('\n')
                    .append(chunk.content())
                    .append("\n\n");
        }
        return rendered.toString().strip();
    }

    /** Desfecho de uma verificação. */
    private record Outcome(Result result, String notes) {

        enum Result {
            SUPPORTED,
            UNSUPPORTED,
            INCONCLUSIVE
        }

        static Outcome supported(String notes) {
            return new Outcome(Result.SUPPORTED, notes);
        }

        static Outcome unsupported(String notes) {
            return new Outcome(Result.UNSUPPORTED, notes);
        }

        static Outcome inconclusive() {
            return new Outcome(Result.INCONCLUSIVE, null);
        }
    }
}
