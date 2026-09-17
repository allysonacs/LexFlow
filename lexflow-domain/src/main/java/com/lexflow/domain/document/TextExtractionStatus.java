package com.lexflow.domain.document;

/** Resultado da extração de texto de um documento. */
public enum TextExtractionStatus {

    /** Há texto aproveitável. */
    EXTRACTED,

    /** O arquivo foi lido, mas nenhum texto foi encontrado — nem pelo OCR. */
    NO_TEXT_FOUND,

    /** O arquivo não pôde ser lido (corrompido, protegido por senha ou em formato inválido). */
    FAILED
}
