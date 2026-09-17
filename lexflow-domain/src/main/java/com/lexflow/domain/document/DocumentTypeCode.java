package com.lexflow.domain.document;

import com.lexflow.domain.exception.InvalidDocumentTypeException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Regra de formato dos códigos de tipo de documento ({@code CONTRACT_DRAFT}, {@code SUPPLIER_CNPJ_CARD}).
 *
 * <p>O mesmo código aparece em dois lugares — na regra de checklist, como documento exigido, e no
 * documento enviado, como tipo informado pelo requisitante —, e o vínculo entre os dois é uma
 * comparação exata (seção 9). Por isso a normalização fica em um ponto só: um "contract_draft" enviado
 * pelo cliente precisa casar com o "CONTRACT_DRAFT" da regra.
 *
 * <p>Os códigos em si não são fixos no código: são configuração de negócio, definida pelas regras de
 * checklist gravadas no banco.
 */
public final class DocumentTypeCode {

    /** Tamanho máximo, compatível com as colunas {@code required_document_type} e {@code document_type}. */
    public static final int MAX_LENGTH = 100;

    private static final Pattern FORMAT = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    private DocumentTypeCode() {
        // classe utilitária
    }

    /**
     * Normaliza o código para {@code UPPER_SNAKE_CASE} e valida o formato.
     *
     * @throws InvalidDocumentTypeException se o código estiver vazio, for longo demais ou tiver
     *     caracteres fora de letras, dígitos e sublinhado
     */
    public static String normalize(String code) {
        String normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > MAX_LENGTH || !FORMAT.matcher(normalized).matches()) {
            throw new InvalidDocumentTypeException(code, MAX_LENGTH);
        }
        return normalized;
    }

    /** Mesma regra, para um código opcional: nulo ou em branco vira nulo. */
    public static String normalizeOptional(String code) {
        return code == null || code.isBlank() ? null : normalize(code);
    }
}
