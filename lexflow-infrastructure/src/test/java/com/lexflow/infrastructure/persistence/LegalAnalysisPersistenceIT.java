package com.lexflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lexflow.application.analysis.LegalAnalysisSchema;
import com.lexflow.domain.ai.AiAnalysisResponse;
import com.lexflow.domain.ai.QuestionKey;
import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseType;
import com.lexflow.infrastructure.persistence.entity.LegalCaseEntity;
import com.lexflow.infrastructure.persistence.mapper.AiAnalysisResponseMapper;
import com.lexflow.infrastructure.persistence.repository.AiAnalysisResponseJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import com.lexflow.infrastructure.persistence.repository.PromptVersionJpaRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * O que a migration {@code V7__legal_analysis.sql} garante no banco (Prompt 13): rastreabilidade de
 * cada resposta, uma resposta por pergunta e o prompt de análise semeado.
 *
 * <p>As restrições são conferidas contra o PostgreSQL real porque é ele quem as aplica: uma regra que
 * só existe no código Java protege apenas o caminho que passa pelo código Java.
 */
class LegalAnalysisPersistenceIT extends AbstractPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    @Autowired
    private AiAnalysisResponseJpaRepository responseRepository;

    @Autowired
    private LegalCaseJpaRepository legalCaseRepository;

    @Autowired
    private PromptVersionJpaRepository promptVersionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("o prompt de análise jurídica está semeado, ativo e com as três seções")
    void shouldSeedTheLegalAnalysisPrompt() {
        var prompt = promptVersionRepository
                .findByPromptKeyAndActiveIsTrue(LegalAnalysisSchema.PROMPT_KEY)
                .orElseThrow();

        assertThat(prompt.getVersion()).isEqualTo(1);
        assertThat(prompt.getTemplateText())
                .contains("### SISTEMA ###", "### USUARIO ###", "### REFORCO ###")
                .contains("{{QUESTION_KEY}}", "{{CHUNKS}}", "{{CHUNK_IDS}}", "{{FACTS}}", "{{CHECKLIST}}")
                .contains("informação não encontrada na base normativa");
    }

    @Test
    @DisplayName("uma resposta do modelo sem versão de prompt é recusada pelo banco")
    void shouldRejectLlmAnswerWithoutPromptVersion() {
        UUID legalCaseId = givenLegalCase();

        // O domínio já recusaria; a inserção direta existe para provar que o banco também recusa.
        assertThatThrownBy(() -> {
            entityManager
                    .createNativeQuery(
                            """
                            INSERT INTO ai_analysis_responses
                                (id, legal_case_id, question_key, answer_text, confidence_score, cited_chunks,
                                 answer_source, model_version, prompt_version_id, created_at)
                            VALUES (:id, :caseId, 'CAN_SIGN_CONTRACT', 'Resposta.', 0.8, CAST('[]' AS jsonb),
                                    'LLM', 'claude-opus-5', NULL, :now)
                            """)
                    .setParameter("id", UUID.randomUUID())
                    .setParameter("caseId", legalCaseId)
                    .setParameter("now", NOW)
                    .executeUpdate();
            entityManager.flush();
        })
                .hasMessageContaining("ck_ai_analysis_responses_traceability");
    }

    @Test
    @DisplayName("uma resposta determinística não pode fingir ter vindo de um modelo")
    void shouldRejectDeterministicAnswerWithModel() {
        UUID legalCaseId = givenLegalCase();

        assertThatThrownBy(() -> {
            entityManager
                    .createNativeQuery(
                            """
                            INSERT INTO ai_analysis_responses
                                (id, legal_case_id, question_key, answer_text, confidence_score, cited_chunks,
                                 answer_source, model_version, prompt_version_id, created_at)
                            VALUES (:id, :caseId, 'HAS_SUFFICIENT_DOCUMENTATION', 'Documentação incompleta.', 1.0,
                                    CAST('[]' AS jsonb), 'DETERMINISTIC', 'claude-opus-5', NULL, :now)
                            """)
                    .setParameter("id", UUID.randomUUID())
                    .setParameter("caseId", legalCaseId)
                    .setParameter("now", NOW)
                    .executeUpdate();
            entityManager.flush();
        })
                .hasMessageContaining("ck_ai_analysis_responses_traceability");
    }

    @Test
    @DisplayName("uma resposta determinística sem modelo e sem trecho citado é aceita")
    void shouldPersistDeterministicAnswer() {
        UUID legalCaseId = givenLegalCase();

        var saved = responseRepository.saveAndFlush(AiAnalysisResponseMapper.toEntity(AiAnalysisResponse.deterministic(
                UUID.randomUUID(),
                legalCaseId,
                QuestionKey.HAS_SUFFICIENT_DOCUMENTATION,
                "Documentação incompleta: faltam os documentos obrigatórios FINANCIAL_OPINION.",
                NOW)));
        entityManager.flush();
        entityManager.clear();

        var reloaded = AiAnalysisResponseMapper.toDomain(
                responseRepository.findById(saved.getId()).orElseThrow());
        assertThat(reloaded.isFromLlm()).isFalse();
        assertThat(reloaded.modelVersion()).isNull();
        assertThat(reloaded.promptVersionId()).isNull();
        assertThat(reloaded.citedChunks()).isEmpty();
    }

    @Test
    @DisplayName("a mesma pergunta não pode ser respondida duas vezes na mesma demanda")
    void shouldRejectDuplicatedAnswerForTheSameQuestion() {
        UUID legalCaseId = givenLegalCase();
        responseRepository.saveAndFlush(AiAnalysisResponseMapper.toEntity(AiAnalysisResponse.deterministic(
                UUID.randomUUID(), legalCaseId, QuestionKey.CAN_SIGN_CONTRACT, "Primeira resposta.", NOW)));

        assertThatThrownBy(() -> {
            responseRepository.saveAndFlush(AiAnalysisResponseMapper.toEntity(AiAnalysisResponse.deterministic(
                    UUID.randomUUID(), legalCaseId, QuestionKey.CAN_SIGN_CONTRACT, "Segunda resposta.", NOW)));
            entityManager.flush();
        })
                .hasMessageContaining("uq_ai_analysis_responses_case_question");
    }

    private UUID givenLegalCase() {
        LegalCaseEntity legalCase = legalCaseRepository.saveAndFlush(new LegalCaseEntity(
                UUID.randomUUID(),
                null,
                LegalCaseType.CONTRACT_SIGNING,
                LegalCaseStatus.AI_ANALYSIS_IN_PROGRESS,
                "ana.silva",
                null,
                CasePriority.NORMAL,
                NOW,
                NOW));
        return legalCase.getId();
    }
}
