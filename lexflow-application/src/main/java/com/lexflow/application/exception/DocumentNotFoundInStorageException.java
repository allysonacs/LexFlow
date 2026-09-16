package com.lexflow.application.exception;

/**
 * Lançada quando o metadado de um documento existe no banco, mas o objeto correspondente não está no
 * storage.
 *
 * <p>É uma inconsistência entre as duas pontas, não um erro do cliente: merece um tipo próprio
 * justamente para não se confundir com "o storage está fora do ar".
 */
public class DocumentNotFoundInStorageException extends DocumentStorageException {

    private final String storagePath;

    public DocumentNotFoundInStorageException(String storagePath) {
        super("Documento não encontrado no storage: %s".formatted(storagePath));
        this.storagePath = storagePath;
    }

    public String storagePath() {
        return storagePath;
    }
}
