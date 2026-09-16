package com.lexflow.infrastructure.storage;

import com.lexflow.application.document.DocumentStoragePort;
import com.lexflow.application.document.DocumentUpload;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implementação provisória de {@link DocumentStoragePort}, válida apenas até o Prompt 06.
 *
 * <p><strong>Ela não grava o binário em lugar nenhum.</strong> O que faz é devolver o caminho sob o
 * qual o arquivo passará a ser gravado quando o adapter de S3/MinIO entrar, de modo que a coluna
 * {@code documents.storage_path} já nasça com o formato definitivo e a ingestão possa ser exercitada
 * de ponta a ponta. O Prompt 06 substitui esta classe pelo adapter real; enquanto os dois
 * coexistirem a aplicação nem sobe, por ambiguidade de bean, o que é melhor do que subir gravando em
 * lugar nenhum sem ninguém perceber.
 *
 * <p>O caminho é derivado dos identificadores da demanda e do documento, e não do nome enviado pelo
 * cliente, porque nome de arquivo não é único nem confiável — o nome original fica em
 * {@code documents.file_name}, e só a extensão é preservada no caminho.
 */
@Component
public class LocalPlaceholderDocumentStorageAdapter implements DocumentStoragePort {

    private static final Logger log = LoggerFactory.getLogger(LocalPlaceholderDocumentStorageAdapter.class);

    @Override
    public String store(UUID legalCaseId, UUID documentId, DocumentUpload upload) {
        String path = "legal-cases/%s/documents/%s%s".formatted(legalCaseId, documentId, extensionOf(upload.fileName()));
        // Metadados apenas: nunca o conteúdo do documento (seção 12 da base de conhecimento).
        log.warn(
                "Storage definitivo ainda não configurado (Prompt 06): o binário de {} bytes do documento {} não foi gravado; caminho reservado: {}",
                upload.sizeInBytes(),
                documentId,
                path);
        return path;
    }

    private String extensionOf(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot < 0 ? "" : fileName.substring(lastDot).toLowerCase(Locale.ROOT);
    }
}
