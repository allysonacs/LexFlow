package com.lexflow.infrastructure.storage;

import com.lexflow.application.document.DocumentStoragePort;
import com.lexflow.application.document.DocumentUpload;
import com.lexflow.application.document.StoredDocument;
import com.lexflow.application.exception.DocumentNotFoundInStorageException;
import com.lexflow.application.exception.DocumentStorageException;
import com.lexflow.domain.document.DocumentFormat;
import com.lexflow.domain.document.Sha256Checksum;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Protege o storage de documentos com retry e circuit breaker (Prompt 17, item 1).
 *
 * <p>Até aqui o storage tinha apenas tempos limite. Faltavam as duas outras proteções que a seção 11
 * exige de toda integração externa, e a diferença é concreta: com o MinIO ou o S3 fora do ar, cada
 * upload esperava o tempo limite inteiro antes de falhar, e uma rajada de ingestões prendia threads
 * até esgotá-las.
 *
 * <p>As tentativas do SDK da AWS foram desligadas em {@link S3ClientConfiguration}: quem repete é o
 * Resilience4j, na instância {@value #RESILIENCE_INSTANCE}. Somar as duas multiplicaria as chamadas e
 * tornaria imprevisível o tempo total de uma falha.
 *
 * <p><strong>O que não é repetido.</strong> {@link DocumentNotFoundInStorageException} é resposta do
 * serviço, não falha dele: o objeto não está lá, e tentar de novo não o faria aparecer. Só
 * {@link DocumentStorageException} conta para o circuito.
 *
 * <p>Repetir é seguro porque a chave do objeto é derivada do conteúdo: a segunda tentativa grava
 * exatamente no mesmo lugar que a primeira teria gravado.
 */
@Component
@Primary
public class ResilientDocumentStorage implements DocumentStoragePort {

    /** Nome da instância do Resilience4j usada por esta integração. */
    public static final String RESILIENCE_INSTANCE = "storage";

    private final S3DocumentStorageAdapter delegate;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public ResilientDocumentStorage(
            S3DocumentStorageAdapter delegate,
            RetryRegistry retryRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.delegate = delegate;
        this.retry = retryRegistry.retry(RESILIENCE_INSTANCE);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE);
    }

    @Override
    public StoredDocument store(
            UUID legalCaseId, DocumentFormat format, Sha256Checksum checksum, DocumentUpload upload) {
        return protect(() -> delegate.store(legalCaseId, format, checksum, upload));
    }

    @Override
    public byte[] retrieve(String storagePath) {
        return protect(() -> delegate.retrieve(storagePath));
    }

    /** Aplica circuit breaker e retry, traduzindo o circuito aberto na exceção da porta. */
    private <T> T protect(Supplier<T> operation) {
        try {
            return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, operation))
                    .get();
        } catch (CallNotPermittedException e) {
            throw new DocumentStorageException(
                    "Circuito do storage de documentos aberto: chamadas suspensas temporariamente", e);
        }
    }
}
