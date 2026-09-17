package com.lexflow.domain.ai;

import com.lexflow.domain.exception.MissingCitedChunksException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Resposta a uma pergunta jurídica, sempre acompanhada da fonte citada e do nível de confiança
 * (seção 10).
 *
 * <p><strong>Resposta do modelo.</strong> O construtor recusa resposta sem trecho citado: essa é a
 * regra que impede uma alucinação de chegar ao revisor humano parecendo fundamentada. A única exceção
 * é a resposta que declara explicitamente que a informação não está na base normativa. Modelo e
 * versão de prompt são obrigatórios — sem eles a resposta deixaria de ser explicável.
 *
 * <p><strong>Resposta determinística.</strong> Quando a resposta vem de uma regra de código
 * ({@link AnswerSource#DETERMINISTIC}), não há modelo, não há prompt e não há trecho a citar: o
 * fundamento é a própria regra, que o revisor confere sozinho. O construtor exige, aí, justamente o
 * contrário — que modelo e versão de prompt estejam ausentes —, para que a origem gravada nunca
 * contradiga o que está registrado.
 */
public record AiAnalysisResponse(
        UUID id,
        UUID legalCaseId,
        QuestionKey questionKey,
        String answerText,
        ConfidenceScore confidenceScore,
        List<UUID> citedChunks,
        AnswerSource answerSource,
        String modelVersion,
        UUID promptVersionId,
        VerificationStatus verificationStatus,
        Instant createdAt) {

    /** Texto que autoriza uma resposta do modelo sem trechos citados. */
    public static final String NOT_FOUND_IN_KNOWLEDGE_BASE = "informação não encontrada na base normativa";

    public AiAnalysisResponse {
        Objects.requireNonNull(id, "id não pode ser nulo");
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        Objects.requireNonNull(questionKey, "questionKey não pode ser nulo");
        Objects.requireNonNull(confidenceScore, "confidenceScore não pode ser nulo");
        Objects.requireNonNull(answerSource, "answerSource não pode ser nulo");
        Objects.requireNonNull(verificationStatus, "verificationStatus não pode ser nulo");
        Objects.requireNonNull(createdAt, "createdAt não pode ser nulo");
        if (answerText == null || answerText.isBlank()) {
            throw new IllegalArgumentException("answerText é obrigatório");
        }
        citedChunks = citedChunks == null ? List.of() : List.copyOf(citedChunks);

        if (answerSource.isFromLlm()) {
            if (modelVersion == null || modelVersion.isBlank()) {
                throw new IllegalArgumentException("modelVersion é obrigatório: rastreabilidade do modelo é exigida");
            }
            Objects.requireNonNull(
                    promptVersionId, "promptVersionId não pode ser nulo: toda chamada ao LLM registra o prompt usado");
            if (citedChunks.isEmpty() && !declaresNotFound(answerText)) {
                throw new MissingCitedChunksException(questionKey.name());
            }
        } else {
            if (modelVersion != null || promptVersionId != null) {
                throw new IllegalArgumentException(
                        "uma resposta determinística não tem modelo nem versão de prompt a registrar");
            }
        }
    }

    /**
     * Resposta do modelo, ainda não submetida à segunda checagem.
     */
    public static AiAnalysisResponse fromLlm(
            UUID id,
            UUID legalCaseId,
            QuestionKey questionKey,
            String answerText,
            ConfidenceScore confidenceScore,
            List<UUID> citedChunks,
            String modelVersion,
            UUID promptVersionId,
            Instant createdAt) {
        return new AiAnalysisResponse(
                id,
                legalCaseId,
                questionKey,
                answerText,
                confidenceScore,
                citedChunks,
                AnswerSource.LLM,
                modelVersion,
                promptVersionId,
                VerificationStatus.NOT_VERIFIED,
                createdAt);
    }

    /**
     * Resposta produzida por regra de código, sem chamar o modelo.
     *
     * <p>A confiança é máxima: uma regra determinística não tem incerteza — ou a documentação
     * obrigatória está completa, ou não está.
     */
    public static AiAnalysisResponse deterministic(
            UUID id, UUID legalCaseId, QuestionKey questionKey, String answerText, Instant createdAt) {
        return new AiAnalysisResponse(
                id,
                legalCaseId,
                questionKey,
                answerText,
                ConfidenceScore.of(1.0),
                List.of(),
                AnswerSource.DETERMINISTIC,
                null,
                null,
                VerificationStatus.NOT_VERIFIED,
                createdAt);
    }

    /** Marca a resposta como confirmada pela segunda checagem, sem alterar o texto original. */
    public AiAnalysisResponse markVerified() {
        return withVerification(VerificationStatus.VERIFIED, confidenceScore);
    }

    /**
     * Marca a resposta como reprovada pela segunda checagem e zera a confiança, para que o revisor
     * humano veja o alerta (Prompt 14). O texto da resposta permanece intacto.
     */
    public AiAnalysisResponse markVerificationFailed() {
        return withVerification(VerificationStatus.FAILED, ConfidenceScore.zero());
    }

    /** Indica se a resposta declara que nada foi encontrado na base normativa. */
    public boolean declaresNotFound() {
        return declaresNotFound(answerText);
    }

    /** Indica se a resposta veio do modelo, e não de uma regra de código. */
    public boolean isFromLlm() {
        return answerSource.isFromLlm();
    }

    private AiAnalysisResponse withVerification(VerificationStatus status, ConfidenceScore score) {
        return new AiAnalysisResponse(
                id,
                legalCaseId,
                questionKey,
                answerText,
                score,
                citedChunks,
                answerSource,
                modelVersion,
                promptVersionId,
                status,
                createdAt);
    }

    private static boolean declaresNotFound(String answerText) {
        return answerText != null
                && answerText.trim().toLowerCase(Locale.ROOT).startsWith(NOT_FOUND_IN_KNOWLEDGE_BASE);
    }
}
