package com.lexflow.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiExtractedFactTest {

    private static final Instant EXTRACTED_AT = Instant.parse("2026-01-10T12:00:00Z");

    @Test
    void shouldCreateFact() {
        AiExtractedFact fact = new AiExtractedFact(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "{\"parties\":[\"ACME\"]}",
                "claude-opus-5",
                EXTRACTED_AT);

        assertThat(fact.extractedJson()).contains("ACME");
        assertThat(fact.modelVersion()).isEqualTo("claude-opus-5");
    }

    @Test
    void shouldRequireJsonAndModelVersion() {
        UUID id = UUID.randomUUID();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AiExtractedFact(id, id, id, " ", "claude-opus-5", EXTRACTED_AT))
                .withMessageContaining("extractedJson");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AiExtractedFact(id, id, id, "{}", null, EXTRACTED_AT))
                .withMessageContaining("modelVersion");

        assertThatNullPointerException()
                .isThrownBy(() -> new AiExtractedFact(id, id, null, "{}", "claude-opus-5", EXTRACTED_AT));
    }
}
