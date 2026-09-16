package com.lexflow.domain.document;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Hash SHA-256 de um documento, usado para detectar reenvio do mesmo arquivo (Prompt 06).
 *
 * <p>Value object: valida o formato na criação e normaliza para minúsculas, para que a comparação de
 * duplicidade não dependa da forma como o hash foi gerado.
 */
public record Sha256Checksum(String value) {

    private static final Pattern HEX_64 = Pattern.compile("^[0-9a-f]{64}$");

    public Sha256Checksum {
        Objects.requireNonNull(value, "checksum não pode ser nulo");
        value = value.trim().toLowerCase(Locale.ROOT);
        if (!HEX_64.matcher(value).matches()) {
            throw new IllegalArgumentException("checksum SHA-256 deve ter 64 caracteres hexadecimais");
        }
    }

    public static Sha256Checksum of(String value) {
        return new Sha256Checksum(value);
    }

    /**
     * Calcula o hash do conteúdo de um arquivo.
     *
     * <p>Usado na ingestão (Prompt 05) para registrar o checksum sem que nenhuma camada externa
     * precise saber qual é o algoritmo combinado.
     */
    public static Sha256Checksum ofContent(byte[] content) {
        Objects.requireNonNull(content, "conteúdo não pode ser nulo");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return new Sha256Checksum(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 é obrigatório em qualquer JVM; se faltar, o ambiente está quebrado.
            throw new IllegalStateException("algoritmo SHA-256 indisponível nesta JVM", e);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
