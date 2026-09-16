package com.lexflow.infrastructure.storage;

import com.lexflow.application.document.DocumentStoragePort;
import com.lexflow.application.document.DocumentUpload;
import com.lexflow.application.document.StoredDocument;
import com.lexflow.application.exception.DocumentNotFoundInStorageException;
import com.lexflow.application.exception.DocumentStorageException;
import com.lexflow.domain.document.DocumentFormat;
import com.lexflow.domain.document.Sha256Checksum;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Armazenamento de documentos em um serviço compatível com S3 (AWS S3 em produção, MinIO em
 * desenvolvimento).
 *
 * <p><strong>O caminho é derivado do conteúdo, não do documento.</strong> A chave do objeto é
 * {@code legal-cases/{legalCaseId}/{checksum}{extensão}}, o que traz três consequências desejadas:
 *
 * <ul>
 *   <li>o mesmo arquivo reenviado para a mesma demanda cai exatamente na mesma chave, de modo que a
 *       idempotência de upload exigida pela seção 11 é estrutural — não depende de uma verificação
 *       que duas réplicas poderiam fazer ao mesmo tempo e ambas concluírem que o objeto não existe;
 *   <li>o prefixo por demanda mantém os objetos de um caso agrupados, o que sustenta uma política de
 *       retenção por demanda (seção 12);
 *   <li>o nome escolhido pelo cliente não compõe o caminho — ele é dado não confiável e não é único.
 *       O nome original continua em {@code documents.file_name} e só a extensão é preservada, para
 *       que o objeto continue reconhecível ao ser inspecionado direto no bucket.
 * </ul>
 *
 * <p>Nenhum log desta classe registra o conteúdo do arquivo: apenas nome, tamanho e checksum, como
 * manda a seção 12.
 */
@Component
public class S3DocumentStorageAdapter implements DocumentStoragePort {

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorageAdapter.class);

    private final S3Client s3Client;
    private final DocumentStorageProperties properties;

    /** Evita uma verificação de bucket a cada gravação depois que ela já deu certo uma vez. */
    private volatile boolean bucketVerified;

    public S3DocumentStorageAdapter(S3Client s3Client, DocumentStorageProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    public StoredDocument store(
            UUID legalCaseId, DocumentFormat format, Sha256Checksum checksum, DocumentUpload upload) {
        String key = storagePath(legalCaseId, checksum, upload.fileName());
        ensureBucketExists();

        if (objectExists(key)) {
            // Mesmo conteúdo, mesma demanda: o objeto que está lá já é exatamente este.
            log.debug(
                    "Documento já presente no storage: arquivo={} checksum={} caminho={}",
                    upload.fileName(),
                    checksum.value(),
                    key);
            return new StoredDocument(key, true);
        }

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(key)
                            .contentType(format.canonicalMimeType())
                            .contentLength((long) upload.sizeInBytes())
                            .build(),
                    RequestBody.fromBytes(upload.content()));
        } catch (SdkException e) {
            throw new DocumentStorageException("Falha ao gravar o documento no storage: %s".formatted(key), e);
        }

        log.info(
                "Documento armazenado: arquivo={} bytes={} checksum={} caminho={}",
                upload.fileName(),
                upload.sizeInBytes(),
                checksum.value(),
                key);
        return new StoredDocument(key, false);
    }

    @Override
    public byte[] retrieve(String storagePath) {
        try {
            ResponseBytes<GetObjectResponse> object = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(storagePath)
                    .build());
            return object.asByteArray();
        } catch (NoSuchKeyException e) {
            throw new DocumentNotFoundInStorageException(storagePath);
        } catch (SdkException e) {
            throw new DocumentStorageException(
                    "Falha ao recuperar o documento do storage: %s".formatted(storagePath), e);
        }
    }

    /**
     * Monta a chave do objeto. É determinística: os mesmos dados de entrada produzem sempre o mesmo
     * caminho, em qualquer réplica.
     */
    static String storagePath(UUID legalCaseId, Sha256Checksum checksum, String fileName) {
        return "legal-cases/%s/%s%s".formatted(legalCaseId, checksum.value(), extensionOf(fileName));
    }

    private boolean objectExists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            // Alguns serviços compatíveis devolvem 404 sem o código de erro tipado do S3.
            if (e.statusCode() == 404) {
                return false;
            }
            throw new DocumentStorageException("Falha ao consultar o documento no storage: %s".formatted(key), e);
        } catch (SdkException e) {
            throw new DocumentStorageException("Falha ao consultar o documento no storage: %s".formatted(key), e);
        }
    }

    /**
     * Garante a existência do bucket quando a configuração pedir.
     *
     * <p>A criação é feita na primeira gravação, e não na inicialização, para que a aplicação suba
     * mesmo sem o storage disponível. Em produção a propriedade fica desligada: criar bucket é tarefa
     * de provisionamento, não da aplicação.
     */
    private void ensureBucketExists() {
        if (bucketVerified || !properties.createBucketIfMissing()) {
            return;
        }
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(properties.bucket()).build());
        } catch (NoSuchBucketException e) {
            createBucket();
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw new DocumentStorageException("Falha ao verificar o bucket '%s'".formatted(properties.bucket()), e);
            }
            createBucket();
        } catch (SdkException e) {
            throw new DocumentStorageException("Falha ao verificar o bucket '%s'".formatted(properties.bucket()), e);
        }
        bucketVerified = true;
    }

    private void createBucket() {
        try {
            s3Client.createBucket(
                    CreateBucketRequest.builder().bucket(properties.bucket()).build());
            log.info("Bucket '{}' criado automaticamente (lexflow.storage.create-bucket-if-missing)", properties.bucket());
        } catch (S3Exception e) {
            // Outra réplica pode ter criado o bucket entre a verificação e a criação.
            if (!"BucketAlreadyOwnedByYou".equals(e.awsErrorDetails().errorCode())) {
                throw new DocumentStorageException("Falha ao criar o bucket '%s'".formatted(properties.bucket()), e);
            }
        } catch (SdkException e) {
            throw new DocumentStorageException("Falha ao criar o bucket '%s'".formatted(properties.bucket()), e);
        }
    }

    private static String extensionOf(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot < 0 ? "" : fileName.substring(lastDot).toLowerCase(Locale.ROOT);
    }
}
