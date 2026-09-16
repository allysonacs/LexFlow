package com.lexflow.application.document;

import com.lexflow.domain.document.DocumentFormat;
import com.lexflow.domain.document.Sha256Checksum;
import java.util.UUID;

/**
 * Porta de saída para o armazenamento do binário de um documento.
 *
 * <p>O binário nunca entra no banco relacional nem no domínio: ele vive em um storage de objetos
 * (S3 em produção, MinIO em desenvolvimento) e o que se persiste em {@code documents} é apenas o
 * metadado, incluindo o caminho devolvido por {@link #store}.
 *
 * <p>O checksum é calculado por quem chama, e não aqui, por dois motivos: ele é um conceito do
 * domínio ({@link Sha256Checksum}) e a mesma demanda precisa dele para gravar o metadado. Passá-lo
 * pronto evita percorrer o arquivo duas vezes e garante que storage e banco enxerguem exatamente o
 * mesmo valor.
 */
public interface DocumentStoragePort {

    /**
     * Grava o binário e devolve o caminho sob o qual ele pode ser recuperado.
     *
     * <p>A operação é idempotente: reenviar o mesmo conteúdo para a mesma demanda não cria um segundo
     * objeto, apenas devolve o caminho do que já está lá.
     *
     * @param legalCaseId demanda à qual o arquivo pertence; separa os objetos por demanda, o que
     *     também sustenta uma eventual política de retenção por caso (seção 12)
     * @param format formato já validado, que define o tipo de conteúdo gravado no objeto
     * @param checksum hash do conteúdo, que compõe o caminho e torna o upload idempotente
     * @throws com.lexflow.application.exception.DocumentStorageException se o storage estiver
     *     indisponível ou recusar a operação
     */
    StoredDocument store(UUID legalCaseId, DocumentFormat format, Sha256Checksum checksum, DocumentUpload upload);

    /**
     * Recupera o binário previamente armazenado.
     *
     * <p>Usado pelo pipeline de extração (Prompt 08), que precisa ler o arquivo bem depois da
     * ingestão, possivelmente em outra réplica.
     *
     * @param storagePath o valor gravado em {@code documents.storage_path}
     * @throws com.lexflow.application.exception.DocumentNotFoundInStorageException se não houver
     *     objeto nesse caminho
     * @throws com.lexflow.application.exception.DocumentStorageException se o storage estiver
     *     indisponível ou recusar a operação
     */
    byte[] retrieve(String storagePath);
}
