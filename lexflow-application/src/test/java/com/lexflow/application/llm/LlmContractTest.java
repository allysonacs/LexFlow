package com.lexflow.application.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.lexflow.application.exception.ApplicationException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Contrato da porta LLM: validações dos pedidos e respostas e o que cada exceção carrega. */
class LlmContractTest {

    @Test
    @DisplayName("o pedido normaliza campos opcionais e oferece variações imutáveis")
    void shouldBuildRequests() {
        LlmRequest text = LlmRequest.text("  ", "Resuma o texto");
        assertThat(text.systemPrompt()).isNull();
        assertThat(text.expectsStructuredOutput()).isFalse();
        assertThat(text.model()).isNull();

        LlmRequest structured = LlmRequest.structured("sistema", "prompt", "{\"type\":\"object\"}")
                .withModel("claude-opus-5")
                .withMaxTokens(512)
                .withEffort(LlmEffort.XHIGH);
        assertThat(structured.expectsStructuredOutput()).isTrue();
        assertThat(structured.model()).isEqualTo("claude-opus-5");
        assertThat(structured.maxTokens()).isEqualTo(512);
        assertThat(structured.effort().apiValue()).isEqualTo("xhigh");
        assertThat(structured.toString())
                .contains("structured=true")
                .contains("promptLength=6")
                .doesNotContain("sistema");
    }

    @Test
    @DisplayName("pedidos inválidos são recusados na construção")
    void shouldValidateRequests() {
        assertThatIllegalArgumentException().isThrownBy(() -> LlmRequest.text(null, " ")).withMessageContaining("prompt");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LlmRequest(null, "p", null, null, 0, null, null))
                .withMessageContaining("maxTokens");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LlmRequest(null, "p", null, null, null, 1.5, null))
                .withMessageContaining("temperature");
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> LlmRequest.structured(null, "p", null));
        assertThat(new LlmRequest(null, "p", " ", " ", null, 0.0, LlmEffort.LOW).outputSchema()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "end_turn, END_TURN",
        "max_tokens, MAX_TOKENS",
        "stop_sequence, STOP_SEQUENCE",
        "tool_use, TOOL_USE",
        "pause_turn, PAUSE_TURN",
        "refusal, REFUSAL",
        "algo_novo, OTHER"
    })
    @DisplayName("os motivos de parada da API são reconhecidos")
    void shouldMapStopReasons(String apiValue, LlmStopReason expected) {
        assertThat(LlmStopReason.fromApiValue(apiValue)).isEqualTo(expected);
    }

    @Test
    @DisplayName("motivo de parada ausente vira OTHER")
    void shouldMapMissingStopReason() {
        assertThat(LlmStopReason.fromApiValue(null)).isEqualTo(LlmStopReason.OTHER);
    }

    @Test
    @DisplayName("a resposta exige o modelo que a gerou e sabe dizer se veio de outro modelo ou truncada")
    void shouldBuildResponses() {
        LlmResponse response = new LlmResponse(
                "msg", "claude-opus-5", "claude-opus-4-8", "texto sigiloso", null,
                LlmStopReason.MAX_TOKENS, new LlmUsage(1, 2, 0, 0), "req", Duration.ofMillis(10));

        assertThat(response.servedByFallbackModel()).isTrue();
        assertThat(response.isTruncated()).isTrue();
        assertThat(response.toString()).contains("claude-opus-4-8").doesNotContain("sigiloso");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LlmResponse(
                        "msg", "claude-opus-5", " ", "t", null, LlmStopReason.END_TURN, LlmUsage.NONE, null, Duration.ZERO))
                .withMessageContaining("model");
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmUsage(-1, 0, 0, 0));
    }

    @Test
    @DisplayName("as exceções são de aplicação e carregam o contexto de diagnóstico")
    void shouldExposeExceptionDetails() {
        LlmUnavailableException unavailable = new LlmUnavailableException("fora do ar", 529);
        LlmUnavailableException timeout = new LlmUnavailableException("tempo", new RuntimeException());
        LlmRequestRejectedException rejected = new LlmRequestRejectedException(401, "authentication_error", "chave");
        LlmResponseValidationException invalid =
                new LlmResponseValidationException("inválida", "claude-opus-5", List.of("$.x: obrigatório"));
        LlmRefusalException refusal = new LlmRefusalException("claude-opus-5", null);

        assertThat(List.of(unavailable, timeout, rejected, invalid, refusal))
                .allSatisfy(e -> assertThat(e).isInstanceOf(LlmException.class).isInstanceOf(ApplicationException.class));
        assertThat(unavailable.statusCode()).isEqualTo(529);
        assertThat(timeout.statusCode()).isZero();
        assertThat(rejected.statusCode()).isEqualTo(401);
        assertThat(rejected.errorType()).isEqualTo("authentication_error");
        assertThat(invalid.model()).isEqualTo("claude-opus-5");
        assertThat(invalid.violations()).containsExactly("$.x: obrigatório");
        assertThat(refusal.category()).isNull();
        assertThat(refusal.model()).isEqualTo("claude-opus-5");
        assertThat(refusal.getMessage()).contains("recusou");
    }
}
