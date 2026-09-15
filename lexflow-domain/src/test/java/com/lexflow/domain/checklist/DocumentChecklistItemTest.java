package com.lexflow.domain.checklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentChecklistItemTest {

    private static final Instant EVALUATED_AT = Instant.parse("2026-01-10T12:00:00Z");

    private final DocumentChecklistItem pendingItem =
            DocumentChecklistItem.pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @Test
    void shouldStartPendingWithoutDocument() {
        assertThat(pendingItem.status()).isEqualTo(ChecklistItemStatus.PENDING);
        assertThat(pendingItem.documentId()).isNull();
        assertThat(pendingItem.evaluatedAt()).isNull();
        assertThat(pendingItem.isSatisfied()).isFalse();
    }

    @Test
    @DisplayName("vincular um documento devolve um novo item satisfeito")
    void shouldSatisfyWithDocument() {
        UUID documentId = UUID.randomUUID();

        DocumentChecklistItem satisfied = pendingItem.satisfyWith(documentId, EVALUATED_AT);

        assertThat(satisfied).isNotSameAs(pendingItem);
        assertThat(satisfied.status()).isEqualTo(ChecklistItemStatus.SATISFIED);
        assertThat(satisfied.documentId()).isEqualTo(documentId);
        assertThat(satisfied.evaluatedAt()).isEqualTo(EVALUATED_AT);
        assertThat(satisfied.isSatisfied()).isTrue();
        assertThat(pendingItem.status()).isEqualTo(ChecklistItemStatus.PENDING);
    }

    @Test
    void shouldMarkMissingAndDropPreviousDocument() {
        DocumentChecklistItem satisfied = pendingItem.satisfyWith(UUID.randomUUID(), EVALUATED_AT);

        DocumentChecklistItem missing = satisfied.markMissing(EVALUATED_AT);

        assertThat(missing.status()).isEqualTo(ChecklistItemStatus.MISSING);
        assertThat(missing.documentId()).isNull();
        assertThat(missing.isSatisfied()).isFalse();
    }

    @Test
    @DisplayName("um item satisfeito sem documento é inconsistente e não pode existir")
    void shouldRejectSatisfiedItemWithoutDocument() {
        UUID id = UUID.randomUUID();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DocumentChecklistItem(
                        id, id, id, ChecklistItemStatus.SATISFIED, null, EVALUATED_AT))
                .withMessageContaining("SATISFIED");
    }

    @Test
    void shouldRejectNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> pendingItem.satisfyWith(null, EVALUATED_AT));
        assertThatNullPointerException().isThrownBy(() -> pendingItem.satisfyWith(UUID.randomUUID(), null));
        assertThatNullPointerException().isThrownBy(() -> pendingItem.markMissing(null));
        assertThatNullPointerException()
                .isThrownBy(() -> DocumentChecklistItem.pending(UUID.randomUUID(), null, UUID.randomUUID()));
    }
}
