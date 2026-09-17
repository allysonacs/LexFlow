package com.lexflow.infrastructure.persistence.entity;

import com.lexflow.domain.ai.AnswerSource;
import com.lexflow.domain.ai.QuestionKey;
import com.lexflow.domain.ai.VerificationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Mapeamento da tabela {@code ai_analysis_responses}.
 *
 * <p>{@code citedChunks} é uma lista de identificadores de {@code knowledge_base_chunks} gravada em
 * coluna {@code jsonb}.
 *
 * <p>{@code modelVersion} e {@code promptVersionId} são nulos nas respostas determinísticas, e a
 * migration V7 garante por restrição de banco que só elas podem tê-los nulos.
 */
@Entity
@Table(name = "ai_analysis_responses")
public class AiAnalysisResponseEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "legal_case_id", nullable = false)
    private UUID legalCaseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_key", nullable = false, length = 50)
    private QuestionKey questionKey;

    @Column(name = "answer_text", nullable = false)
    private String answerText;

    @Column(name = "confidence_score", nullable = false)
    private double confidenceScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cited_chunks", nullable = false)
    private List<UUID> citedChunks;

    @Enumerated(EnumType.STRING)
    @Column(name = "answer_source", nullable = false, length = 20)
    private AnswerSource answerSource;

    @Column(name = "model_version", length = 100)
    private String modelVersion;

    @Column(name = "prompt_version_id")
    private UUID promptVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    private VerificationStatus verificationStatus;

    @Column(name = "verification_notes")
    private String verificationNotes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AiAnalysisResponseEntity() {
        // exigido pelo JPA
    }

    public AiAnalysisResponseEntity(
            UUID id,
            UUID legalCaseId,
            QuestionKey questionKey,
            String answerText,
            double confidenceScore,
            List<UUID> citedChunks,
            AnswerSource answerSource,
            String modelVersion,
            UUID promptVersionId,
            VerificationStatus verificationStatus,
            String verificationNotes,
            Instant createdAt) {
        this.id = id;
        this.legalCaseId = legalCaseId;
        this.questionKey = questionKey;
        this.answerText = answerText;
        this.confidenceScore = confidenceScore;
        this.citedChunks = citedChunks;
        this.answerSource = answerSource;
        this.modelVersion = modelVersion;
        this.promptVersionId = promptVersionId;
        this.verificationStatus = verificationStatus;
        this.verificationNotes = verificationNotes;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getLegalCaseId() {
        return legalCaseId;
    }

    public QuestionKey getQuestionKey() {
        return questionKey;
    }

    public String getAnswerText() {
        return answerText;
    }

    public double getConfidenceScore() {
        return confidenceScore;
    }

    public List<UUID> getCitedChunks() {
        return citedChunks;
    }

    public AnswerSource getAnswerSource() {
        return answerSource;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public UUID getPromptVersionId() {
        return promptVersionId;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public String getVerificationNotes() {
        return verificationNotes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
