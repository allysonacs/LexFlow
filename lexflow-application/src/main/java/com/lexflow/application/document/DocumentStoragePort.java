package com.lexflow.application.document;

import java.util.UUID;

/**
 * Porta de saída para o armazenamento do binário de um documento.
 *
 * <p>O adapter definitivo — S3 em produção, MinIO em desenvolvimento — entra no Prompt 06. Até lá a
 * infraestrutura fornece uma implementação provisória que apenas calcula o caminho, sem gravar o
 * arquivo. A ingestão já conversa com esta interface, de modo que a chegada do adapter real não
 * exige mudança nenhuma no caso de uso.
 */
public interface DocumentStoragePort {

    /**
     * Grava o binário e devolve o caminho sob o qual ele pode ser recuperado.
     *
     * @param legalCaseId demanda à qual o arquivo pertence
     * @param documentId identificador já atribuído ao documento, usado para compor um caminho estável
     * @return caminho de armazenamento, gravado em {@code documents.storage_path}
     */
    String store(UUID legalCaseId, UUID documentId, DocumentUpload upload);
}
