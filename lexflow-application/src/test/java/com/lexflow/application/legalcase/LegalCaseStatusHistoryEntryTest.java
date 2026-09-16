package com.lexflow.application.legalcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.lexflow.domain.legalcase.LegalCaseStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LegalCaseStatusHistoryEntryTest {

    private static final Instant CHANGED_AT = Instant.parse("2026-03-05T14:30:00Z");

    @Test
    void shouldCreateEntry() {
        LegalCaseStatusHistoryEntry entry = new LegalCaseStatusHistoryEntry(
                UUID.randomUUID(),
                UUID.randomUUID(),
                LegalCaseStatus.RECEIVED,
                LegalCaseStatus.CLASSIFYING,
                CHANGED_AT,
                LegalCaseStatusHistoryEntry.SYSTEM_ACTOR,
                "Evento de ingestão consumido");

        assertThat(entry.isInitial()).isFalse();
        assertThat(entry.changedBy()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("sem status anterior, o registro é o que abre o histórico")
    void shouldFlagInitialEntry() {
        LegalCaseStatusHistoryEntry entry = new LegalCaseStatusHistoryEntry(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                LegalCaseStatus.RECEIVED,
                CHANGED_AT,
                "ingestao-api",
                null);

        assertThat(entry.isInitial()).isTrue();
        assertThat(entry.reason()).isNull();
    }

    @Test
    void shouldValidateRequiredFields() {
        UUID id = UUID.randomUUID();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LegalCaseStatusHistoryEntry(
                        id, id, null, LegalCaseStatus.RECEIVED, CHANGED_AT, " ", null))
                .withMessageContaining("changedBy");

        assertThatNullPointerException()
                .isThrownBy(() -> new LegalCaseStatusHistoryEntry(id, id, null, null, CHANGED_AT, "SYSTEM", null));
        assertThatNullPointerException()
                .isThrownBy(() -> new LegalCaseStatusHistoryEntry(
                        id, null, null, LegalCaseStatus.RECEIVED, CHANGED_AT, "SYSTEM", null));
        assertThatNullPointerException()
                .isThrownBy(() -> new LegalCaseStatusHistoryEntry(
                        id, id, null, LegalCaseStatus.RECEIVED, null, "SYSTEM", null));
    }
}
