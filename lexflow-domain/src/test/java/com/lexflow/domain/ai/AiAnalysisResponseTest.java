package com.lexflow.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lexflow.domain.exception.MissingCitedChunksException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiAnalysisResponseTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-10T12:00:00Z");
    private static final UUID CHUNK = UUID.randomUUID();

    private AiAnalysisResponse response(String answerText, List<UUID> citedChunks) {
        return AiAnalysisResponse.fromLlm(
                UUID.randomUUID(),
                UUID.randomUUID(),
                QuestionKey.CAN_SIGN_CONTRACT,
                answerText,
                ConfidenceScore.of(0.82),
                citedChunks,
                "claude-opus-5",
                UUID.randomUUID(),
                CREATED_AT);
    }

    @Test
    void shouldCreateResponseWithCitedChunks() {
        AiAnalysisResponse response = response("O contrato pode ser assinado.", List.of(CHUNK));

        assertThat(response.citedChunks()).containsExactly(CHUNK);
        assertThat(response.verificationStatus()).isEqualTo(VerificationStatus.NOT_VERIFIED);
        assertThat(response.declaresNotFound()).isFalse();
    }

    @Test
    @DisplayName("resposta sem trecho citado é recusada: é a barreira contra alucinação")
    void shouldRejectAnswerWithoutCitedChunks() {
        assertThatExceptionOfType(MissingCitedChunksException.class)
                .isThrownBy(() -> response("O contrato pode ser assinado.", List.of()))
                .withMessageContaining("CAN_SIGN_CONTRACT");

        assertThatExceptionOfType(MissingCitedChunksException.class)
                .isThrownBy(() -> response("O contrato pode ser assinado.", null));
    }

    @Test
    @DisplayName("a única resposta permitida sem citação é a que declara que nada foi encontrado")
    void shouldAcceptNotFoundAnswerWithoutCitedChunks() {
        AiAnalysisResponse response =
                response(AiAnalysisResponse.NOT_FOUND_IN_KNOWLEDGE_BASE + " para esta cláusula.", List.of());

        assertThat(response.citedChunks()).isEmpty();
        assertThat(response.declaresNotFound()).isTrue();
    }

    @Test
    void shouldNormalizeCitedChunksToAnImmutableCopy() {
        List<UUID> mutableChunks = new ArrayList<>(List.of(CHUNK));
        AiAnalysisResponse response = response("Resposta fundamentada.", mutableChunks);

        mutableChunks.clear();

        assertThat(response.citedChunks()).containsExactly(CHUNK);
        assertThatThrownBy(() -> response.citedChunks().add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a segunda checagem sinaliza a resposta sem reescrevê-la")
    void shouldFlagVerificationResultWithoutChangingTheAnswer() {
        AiAnalysisResponse original = response("O contrato pode ser assinado.", List.of(CHUNK));

        AiAnalysisResponse verified = original.markVerified("O trecho citado prevê exatamente essa alçada.");
        AiAnalysisResponse failed = original.markVerificationFailed("O trecho citado trata de outro assunto.");

        assertThat(verified.verificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(verified.confidenceScore()).isEqualTo(original.confidenceScore());
        assertThat(failed.verificationStatus()).isEqualTo(VerificationStatus.FAILED);
        assertThat(failed.confidenceScore().value()).isZero();
        assertThat(failed.answerText()).isEqualTo(original.answerText());
        assertThat(failed.verificationNotes()).isEqualTo("O trecho citado trata de outro assunto.");
        assertThat(verified.verificationNotes()).contains("alçada");
        assertThat(original.verificationStatus()).isEqualTo(VerificationStatus.NOT_VERIFIED);
        assertThat(original.awaitsVerification()).isTrue();
        assertThat(verified.awaitsVerification()).isFalse();
    }

    @Test
    @DisplayName("uma resposta determinística não cita trecho, e também não finge ter vindo de um modelo")
    void shouldAcceptDeterministicAnswerWithoutCitedChunks() {
        AiAnalysisResponse response = AiAnalysisResponse.deterministic(
                UUID.randomUUID(),
                UUID.randomUUID(),
                QuestionKey.HAS_SUFFICIENT_DOCUMENTATION,
                "Documentação incompleta: faltam os documentos obrigatórios CONTRACT_DRAFT.",
                CREATED_AT);

        assertThat(response.isFromLlm()).isFalse();
        assertThat(response.citedChunks()).isEmpty();
        assertThat(response.modelVersion()).isNull();
        assertThat(response.promptVersionId()).isNull();
        // Uma regra determinística não tem incerteza: ou falta documento obrigatório, ou não falta.
        assertThat(response.confidenceScore().value()).isEqualTo(1.0);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AiAnalysisResponse(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        QuestionKey.HAS_SUFFICIENT_DOCUMENTATION,
                        "Documentação incompleta.",
                        ConfidenceScore.of(1.0),
                        List.of(),
                        AnswerSource.DETERMINISTIC,
                        "claude-opus-5",
                        null,
                        VerificationStatus.NOT_VERIFIED,
                        null,
                        CREATED_AT))
                .withMessageContaining("determinística");
    }

    @Test
    void shouldRequireTraceabilityFields() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AiAnalysisResponse(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        QuestionKey.CAN_SIGN_CONTRACT,
                        "Resposta.",
                        ConfidenceScore.of(0.5),
                        List.of(CHUNK),
                        AnswerSource.LLM,
                        " ",
                        UUID.randomUUID(),
                        VerificationStatus.NOT_VERIFIED,
                        null,
                        CREATED_AT))
                .withMessageContaining("modelVersion");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> response("   ", List.of(CHUNK)))
                .withMessageContaining("answerText");

        assertThatThrownBy(() -> AiAnalysisResponse.fromLlm(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        QuestionKey.CAN_SIGN_CONTRACT,
                        "Resposta.",
                        ConfidenceScore.of(0.5),
                        List.of(CHUNK),
                        "claude-opus-5",
                        null,
                        CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("promptVersionId");
    }
}
