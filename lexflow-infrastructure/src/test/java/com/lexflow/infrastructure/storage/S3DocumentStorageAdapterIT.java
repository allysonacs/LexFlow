package com.lexflow.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lexflow.application.document.DocumentUpload;
import com.lexflow.application.document.StoredDocument;
import com.lexflow.application.exception.DocumentNotFoundInStorageException;
import com.lexflow.domain.document.DocumentFormat;
import com.lexflow.domain.document.Sha256Checksum;
import com.lexflow.infrastructure.testsupport.MinioTestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Testes de integração do adapter de storage contra um MinIO real.
 *
 * <p>Sem Spring de propósito: o que interessa aqui é o comportamento do adapter e da montagem do
 * cliente, e um teste de unidade com container sobe em uma fração do tempo de um contexto completo.
 * A fiação com o resto da aplicação é coberta pelos testes de {@code lexflow-api}.
 *
 * <p>Requer Docker em execução.
 */
@Testcontainers
class S3DocumentStorageAdapterIT {

    private static final String BUCKET = "lexflow-documents-it";

    @Container
    private static final MinIOContainer MINIO = new MinIOContainer(MinioTestcontainersConfiguration.MINIO_IMAGE)
            .withUserName(MinioTestcontainersConfiguration.TEST_USER)
            .withPassword(MinioTestcontainersConfiguration.TEST_PASSWORD);

    private static S3Client s3Client;
    private static S3DocumentStorageAdapter adapter;

    @BeforeAll
    static void setUp() {
        DocumentStorageProperties properties = new DocumentStorageProperties(
                BUCKET,
                "us-east-1",
                MINIO.getS3URL(),
                MINIO.getUserName(),
                MINIO.getPassword(),
                true,
                // O bucket não existe no início: o próprio adapter deve criá-lo na primeira gravação.
                true);
        // Reaproveita a configuração de produção, para que um erro de montagem do cliente apareça aqui.
        s3Client = new S3ClientConfiguration().s3Client(properties);
        adapter = new S3DocumentStorageAdapter(s3Client, properties);
    }

    private static DocumentUpload upload(String fileName, String content) {
        return new DocumentUpload(fileName, null, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Sha256Checksum checksumOf(DocumentUpload upload) {
        return Sha256Checksum.ofContent(upload.content());
    }

    private static List<S3Object> objectsOf(UUID legalCaseId) {
        return s3Client
                .listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(BUCKET)
                        .prefix("legal-cases/%s/".formatted(legalCaseId))
                        .build())
                .contents();
    }

    @Test
    @DisplayName("upload e download de um arquivo funcionam de ponta a ponta")
    void shouldStoreAndRetrieveDocument() {
        UUID legalCaseId = UUID.randomUUID();
        DocumentUpload upload = upload("contrato.pdf", "conteúdo do contrato");

        StoredDocument stored = adapter.store(legalCaseId, DocumentFormat.PDF, checksumOf(upload), upload);

        assertThat(stored.alreadyPresent()).isFalse();
        assertThat(stored.storagePath())
                .isEqualTo("legal-cases/%s/%s.pdf".formatted(legalCaseId, checksumOf(upload).value()));
        assertThat(adapter.retrieve(stored.storagePath())).isEqualTo(upload.content());
    }

    @Test
    @DisplayName("reenvio do mesmo arquivo para a mesma demanda não gera um segundo objeto")
    void shouldNotStoreTheSameContentTwice() {
        UUID legalCaseId = UUID.randomUUID();
        DocumentUpload upload = upload("acordo.pdf", "mesmo conteúdo");

        StoredDocument first = adapter.store(legalCaseId, DocumentFormat.PDF, checksumOf(upload), upload);
        StoredDocument second = adapter.store(legalCaseId, DocumentFormat.PDF, checksumOf(upload), upload);

        assertThat(first.alreadyPresent()).isFalse();
        assertThat(second.alreadyPresent()).isTrue();
        assertThat(second.storagePath()).isEqualTo(first.storagePath());
        assertThat(objectsOf(legalCaseId)).hasSize(1);
    }

    @Test
    @DisplayName("o mesmo conteúdo com outro nome também não duplica o objeto")
    void shouldDeduplicateByContentNotByFileName() {
        UUID legalCaseId = UUID.randomUUID();
        DocumentUpload original = upload("contrato.pdf", "conteúdo idêntico");
        DocumentUpload renamed = upload("contrato-assinado.pdf", "conteúdo idêntico");

        adapter.store(legalCaseId, DocumentFormat.PDF, checksumOf(original), original);
        StoredDocument second = adapter.store(legalCaseId, DocumentFormat.PDF, checksumOf(renamed), renamed);

        assertThat(second.alreadyPresent()).isTrue();
        assertThat(objectsOf(legalCaseId)).hasSize(1);
    }

    @Test
    @DisplayName("arquivos diferentes na mesma demanda geram objetos distintos")
    void shouldStoreDistinctContents() {
        UUID legalCaseId = UUID.randomUUID();
        DocumentUpload contract = upload("contrato.pdf", "um conteúdo");
        DocumentUpload attachment = upload("anexo.png", "outro conteúdo");

        adapter.store(legalCaseId, DocumentFormat.PDF, checksumOf(contract), contract);
        adapter.store(legalCaseId, DocumentFormat.PNG, checksumOf(attachment), attachment);

        assertThat(objectsOf(legalCaseId)).hasSize(2);
    }

    @Test
    @DisplayName("o mesmo conteúdo em demandas diferentes é gravado uma vez por demanda")
    void shouldIsolateObjectsByLegalCase() {
        DocumentUpload upload = upload("procuracao.pdf", "conteúdo compartilhado");
        UUID firstCase = UUID.randomUUID();
        UUID secondCase = UUID.randomUUID();

        StoredDocument first = adapter.store(firstCase, DocumentFormat.PDF, checksumOf(upload), upload);
        StoredDocument second = adapter.store(secondCase, DocumentFormat.PDF, checksumOf(upload), upload);

        assertThat(second.alreadyPresent()).isFalse();
        assertThat(second.storagePath()).isNotEqualTo(first.storagePath());
        assertThat(objectsOf(firstCase)).hasSize(1);
        assertThat(objectsOf(secondCase)).hasSize(1);
    }

    @Test
    @DisplayName("o tipo de conteúdo gravado é o canônico do formato, não o declarado pelo cliente")
    void shouldStoreCanonicalContentType() {
        UUID legalCaseId = UUID.randomUUID();
        DocumentUpload disguised =
                new DocumentUpload("foto.png", "application/octet-stream", "png".getBytes(StandardCharsets.UTF_8));

        StoredDocument stored = adapter.store(legalCaseId, DocumentFormat.PNG, checksumOf(disguised), disguised);

        assertThat(objectsOf(legalCaseId)).hasSize(1);
        assertThat(s3Client
                        .headObject(builder -> builder.bucket(BUCKET).key(stored.storagePath()))
                        .contentType())
                .isEqualTo("image/png");
    }

    @Test
    @DisplayName("recuperar um caminho inexistente resulta em exceção específica")
    void shouldFailWhenDocumentIsNotInStorage() {
        String missingPath = "legal-cases/%s/inexistente.pdf".formatted(UUID.randomUUID());

        assertThatExceptionOfType(DocumentNotFoundInStorageException.class)
                .isThrownBy(() -> adapter.retrieve(missingPath))
                .satisfies(e -> assertThat(e.storagePath()).isEqualTo(missingPath));
    }

    @Test
    @DisplayName("o caminho é determinístico: mesmos dados de entrada, mesmo caminho")
    void shouldGenerateDeterministicPath() {
        UUID legalCaseId = UUID.randomUUID();
        Sha256Checksum checksum = Sha256Checksum.ofContent("x".getBytes(StandardCharsets.UTF_8));

        assertThat(S3DocumentStorageAdapter.storagePath(legalCaseId, checksum, "Contrato.PDF"))
                .isEqualTo(S3DocumentStorageAdapter.storagePath(legalCaseId, checksum, "contrato.pdf"))
                .isEqualTo("legal-cases/%s/%s.pdf".formatted(legalCaseId, checksum.value()));
    }
}
