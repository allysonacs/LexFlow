package com.lexflow.domain.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Sha256ChecksumTest {

    private static final String VALID = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void shouldAcceptValidHash() {
        assertThat(Sha256Checksum.of(VALID).value()).isEqualTo(VALID);
        assertThat(Sha256Checksum.of(VALID)).hasToString(VALID);
    }

    @Test
    @DisplayName("normaliza maiúsculas e espaços, para a comparação de duplicidade não falhar à toa")
    void shouldNormalizeInput() {
        assertThat(Sha256Checksum.of("  " + VALID.toUpperCase() + "  ")).isEqualTo(Sha256Checksum.of(VALID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc", "g3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"})
    void shouldRejectInvalidHash(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Sha256Checksum.of(value))
                .withMessageContaining("64 caracteres hexadecimais");
    }

    @Test
    void shouldRejectNull() {
        assertThatNullPointerException().isThrownBy(() -> Sha256Checksum.of(null));
    }

    @Test
    @DisplayName("o hash do conteúdo vazio é o valor conhecido do SHA-256")
    void shouldHashEmptyContent() {
        assertThat(Sha256Checksum.ofContent(new byte[0])).isEqualTo(Sha256Checksum.of(VALID));
    }

    @Test
    @DisplayName("conteúdos iguais geram o mesmo hash e conteúdos diferentes, hashes diferentes")
    void shouldHashContent() {
        byte[] content = "conteúdo do contrato".getBytes(StandardCharsets.UTF_8);

        assertThat(Sha256Checksum.ofContent(content)).isEqualTo(Sha256Checksum.ofContent(content.clone()));
        assertThat(Sha256Checksum.ofContent(content))
                .isNotEqualTo(Sha256Checksum.ofContent("outro conteúdo".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shouldRejectNullContent() {
        assertThatNullPointerException().isThrownBy(() -> Sha256Checksum.ofContent(null));
    }
}
