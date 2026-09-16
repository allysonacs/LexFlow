/**
 * Portas e estruturas de dados ligadas aos arquivos anexados a uma demanda.
 *
 * <p>O binário nunca chega ao domínio: ele vai para o storage pela porta
 * {@link com.lexflow.application.document.DocumentStoragePort} e o que se persiste é apenas o
 * metadado {@link com.lexflow.domain.document.Document}.
 */
package com.lexflow.application.document;
