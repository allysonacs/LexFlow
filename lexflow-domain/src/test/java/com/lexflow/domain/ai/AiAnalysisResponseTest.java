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
        return AiAnalysisResponse.of(
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

        AiAnalysisResponse verified = original.markVerified();
        AiAnalysisResponse failed = original.markVerificationFailed();

        assertThat(verified.verificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(verified.confidenceScore()).isEqualTo(original.confidenceScore());
        assertThat(failed.verificationStatus()).isEqualTo(VerificationStatus.FAILED);
        assertThat(failed.confidenceScore().value()).isZero();
        assertThat(failed.answerText()).isEqualTo(original.answerText());
        assertThat(original.verificationStatus()).isEqualTo(VerificationStatus.NOT_VERIFIED);
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
                        " ",
                        UUID.randomUUID(),
                        VerificationStatus.NOT_VERIFIED,
                        CREATED_AT))
                .withMessageContaining("modelVersion");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> response("   ", List.of(CHUNK)))
                .withMessageContaining("answerText");
    }
}
