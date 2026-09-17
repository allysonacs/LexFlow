package com.lexflow.domain.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.lexflow.domain.ai.PromptVersion;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LegalCaseAlertTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    @Test
    @DisplayName("um alerta aberto bloqueia o pipeline; resolvido, não")
    void shouldBlockWhileOpen() {
        LegalCaseAlert alert = LegalCaseAlert.open(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                LegalCaseAlertType.FACT_EXTRACTION_INVALID_OUTPUT, "  saída inválida  ", NOW);

        assertThat(alert.isOpen()).isTrue();
        assertThat(alert.blocksPipeline()).isTrue();
        assertThat(alert.message()).isEqualTo("saída inválida");

        LegalCaseAlert resolved = new LegalCaseAlert(
                alert.id(), alert.legalCaseId(), null, alert.type(), alert.message(), NOW, NOW.plusSeconds(1));
        assertThat(resolved.isOpen()).isFalse();
        assertThat(resolved.blocksPipeline()).isFalse();
    }

    @Test
    @DisplayName("mensagem longa é truncada; dados incoerentes são recusados")
    void shouldValidate() {
        UUID id = UUID.randomUUID();

        assertThat(LegalCaseAlert.open(id, id, null, LegalCaseAlertType.FACT_EXTRACTION_REFUSED, "x".repeat(3000), NOW)
                        .message())
                .hasSize(LegalCaseAlert.MESSAGE_MAX_LENGTH);
        assertThatIllegalArgumentException().isThrownBy(() ->
                LegalCaseAlert.open(id, id, null, LegalCaseAlertType.FACT_EXTRACTION_REFUSED, " ", NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> new LegalCaseAlert(
                id, id, null, LegalCaseAlertType.FACT_EXTRACTION_REFUSED, "m", NOW, NOW.minusSeconds(1)));
        assertThatNullPointerException().isThrownBy(() ->
                LegalCaseAlert.open(id, id, null, null, "m", NOW));
    }

    @Test
    @DisplayName("a versão de prompt exige chave, versão positiva e texto")
    void shouldValidatePromptVersion() {
        PromptVersion version = new PromptVersion(UUID.randomUUID(), "FACT_EXTRACTION", 2, "template", true, NOW);

        assertThat(version.label()).isEqualTo("FACT_EXTRACTION v2");
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PromptVersion(UUID.randomUUID(), " ", 1, "t", true, NOW));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PromptVersion(UUID.randomUUID(), "K", 0, "t", true, NOW));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PromptVersion(UUID.randomUUID(), "K", 1, " ", true, NOW));
    }
}
