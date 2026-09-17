package com.lexflow.domain.document;

/** Como o texto de um documento foi obtido. */
public enum TextExtractionMethod {

    /** Lido da camada de texto do próprio arquivo (PDF com texto, DOCX). */
    NATIVE_TEXT,

    /** Reconhecido por OCR, a partir de uma imagem ou de um PDF digitalizado sem camada de texto. */
    OCR
}
