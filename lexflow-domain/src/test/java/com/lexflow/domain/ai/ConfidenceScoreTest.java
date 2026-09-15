package com.lexflow.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.domain.exception.InvalidConfidenceScoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfidenceScoreTest {

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.5, 0.999, 1.0})
    void shouldAcceptValuesInsideTheRange(double value) {
        assertThat(ConfidenceScore.of(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.0001, -1.0, 1.0001, 42.0, Double.NaN, Double.POSITIVE_INFINITY})
    void shouldRejectValuesOutsideTheRange(double value) {
        assertThatExceptionOfType(InvalidConfidenceScoreException.class)
                .isThrownBy(() -> ConfidenceScore.of(value))
                .withMessageContaining("0.0 e 1.0");
    }

    @Test
    void zeroShouldBeTheLowestPossibleConfidence() {
        assertThat(ConfidenceScore.zero().value()).isZero();
    }

    @Test
    void shouldCompareAgainstThreshold() {
        assertThat(ConfidenceScore.of(0.4).isBelow(0.7)).isTrue();
        assertThat(ConfidenceScore.of(0.7).isBelow(0.7)).isFalse();
    }
}
