package com.lexflow.application.document;

import java.util.Objects;

/**
 * Resultado do armazenamento de um arquivo.
 *
 * @param storagePath caminho determinístico sob o qual o binário pode ser recuperado; é o que vai
 *     para a coluna {@code documents.storage_path}
 * @param alreadyPresent {@code true} quando o objeto já existia com o mesmo conteúdo e nada foi
 *     enviado de novo — é a idempotência de upload exigida pela seção 11 da base de conhecimento
 */
public record StoredDocument(String storagePath, boolean alreadyPresent) {

    public StoredDocument {
        if (storagePath == null || storagePath.isBlank()) {
            throw new IllegalArgumentException("storagePath é obrigatório");
        }
    }
}
