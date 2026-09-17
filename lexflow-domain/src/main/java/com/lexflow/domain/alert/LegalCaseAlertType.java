package com.lexflow.domain.alert;

/**
 * Motivos pelos quais o pipeline para e pede atenção humana.
 *
 * <p>Enquanto uma demanda tem um alerta destes em aberto, ela não avança automaticamente.
 */
public enum LegalCaseAlertType {

    /** O LLM devolveu fatos fora do formato esperado, mesmo depois da nova tentativa com o formato reforçado. */
    FACT_EXTRACTION_INVALID_OUTPUT,

    /** O LLM se recusou a extrair os fatos de um documento. */
    FACT_EXTRACTION_REFUSED,

    /** O texto do documento é longo demais para ser enviado inteiro — e ele nunca é truncado em silêncio. */
    DOCUMENT_TOO_LONG_FOR_EXTRACTION
}
