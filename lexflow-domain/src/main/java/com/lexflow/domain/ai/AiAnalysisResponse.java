package com.lexflow.domain.ai;

import com.lexflow.domain.exception.MissingCitedChunksException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Resposta da IA a uma pergunta jurídica, sempre acompanhada da fonte citada e do nível de confiança
 * (seção 10).
 *
 * <p>O construtor recusa resposta sem trecho citado: essa é a regra que impede uma alucinação de
 * chegar ao revisor humano parecendo fundamentada. A única exceção é a resposta que declara
 * explicitamente que a informação não está na base normativa.
 */
public record AiAnalysisResponse(
        UUID id,
        UUID legalCaseId,
        QuestionKey questionKey,
        String answerText,
        ConfidenceScore confidenceScore,
        List<UUID> citedChunks,
        String modelVersion,
        UUID promptVersionId,
        VerificationStatus verificationStatus,
        Instant createdAt) {

    /** Texto que autoriza uma resposta sem trechos citados. */
    public static final String NOT_FOUND_IN_KNOWLEDGE_BASE = "informação não encontrada na base normativa";

    public AiAnalysisResponse {
        Objects.requireNonNull(id, "id não pode ser nulo");
        Objects.requireNonNull(legalCaseId, "legalCaseId não pode ser nulo");
        Objects.requireNonNull(questionKey, "questionKey não pode ser nulo");
        Objects.requireNonNull(confidenceScore, "confidenceScore não pode ser nulo");
        Objects.requireNonNull(verificationStatus, "verificationStatus não pode ser nulo");
        Objects.requireNonNull(createdAt, "createdAt não pode ser nulo");
        if (answerText == null || answerText.isBlank()) {
            throw new IllegalArgumentException("answerText é obrigatório");
        }
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion é obrigatório: rastreabilidade do modelo é exigida");
        }
        citedChunks = citedChunks == null ? List.of() : List.copyOf(citedChunks);
        if (citedChunks.isEmpty() && !declaresNotFound(answerText)) {
            throw new MissingCitedChunksException(questionKey.name());
        }
    }

    /**
     * Cria uma resposta ainda não submetida à segunda checagem.
     */
    public static AiAnalysisResponse of(
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
                modelVersion,
                promptVersionId,
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

    private AiAnalysisResponse withVerification(VerificationStatus status, ConfidenceScore score) {
        return new AiAnalysisResponse(
                id,
                legalCaseId,
                questionKey,
                answerText,
                score,
                citedChunks,
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
