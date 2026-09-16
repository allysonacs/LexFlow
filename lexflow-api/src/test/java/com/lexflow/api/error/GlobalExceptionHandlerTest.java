package com.lexflow.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.lexflow.application.exception.DocumentNotFoundInStorageException;
import com.lexflow.application.exception.DocumentStorageException;
import com.lexflow.application.exception.IdempotentRequestInProgressException;
import com.lexflow.domain.exception.InvalidStatusTransitionException;
import com.lexflow.domain.exception.LegalCaseNotFoundException;
import com.lexflow.domain.exception.UnknownLegalCaseTypeException;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Fixa o contrato de erro da API.
 *
 * <p>São os códigos e os status que o cliente trata do outro lado: mudá-los sem querer quebra
 * integrações, e este teste é o que torna a mudança visível.
 */
class GlobalExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/legal-cases");
        request.setRequestURI("/api/v1/legal-cases");
        return request;
    }

    @Test
    @DisplayName("demanda inexistente vira 404")
    void shouldMapNotFound() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleNotFound(new LegalCaseNotFoundException(UUID.randomUUID()), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo(ApiErrorCodes.RESOURCE_NOT_FOUND);
        assertThat(response.getBody().timestamp()).isEqualTo(NOW);
        assertThat(response.getBody().path()).isEqualTo("/api/v1/legal-cases");
    }

    @Test
    @DisplayName("tipo de demanda desconhecido vira 400, com a mensagem do domínio")
    void shouldMapDomainViolationToBadRequest() {
        ResponseEntity<ApiErrorResponse> response = handler.handleDomain(
                new UnknownLegalCaseTypeException("CONTRATO", LegalCaseType.supportedValues()), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ApiErrorCodes.INVALID_REQUEST);
        assertThat(response.getBody().message()).contains("Tipo de demanda inválido");
    }

    @Test
    @DisplayName("transição de status recusada vira 409")
    void shouldMapInvalidTransitionToConflict() {
        ResponseEntity<ApiErrorResponse> response = handler.handleInvalidTransition(
                new InvalidStatusTransitionException(LegalCaseStatus.CLOSED, LegalCaseStatus.RECEIVED), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo(ApiErrorCodes.CONFLICT);
    }

    @Test
    @DisplayName("ingestão em andamento com a mesma chave vira 409")
    void shouldMapIdempotencyConflict() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleIdempotencyConflict(new IdempotentRequestInProgressException("chave-1"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message()).contains("chave-1");
    }

    @Test
    @DisplayName("falha do storage vira 503, sem expor o detalhe interno")
    void shouldMapStorageFailureToServiceUnavailable() {
        ResponseEntity<ApiErrorResponse> response = handler.handleStorageFailure(
                new DocumentStorageException("bucket lexflow-prod recusou a conexão"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo(ApiErrorCodes.STORAGE_UNAVAILABLE);
        assertThat(response.getBody().message()).doesNotContain("bucket");
    }

    @Test
    @DisplayName("documento ausente no storage também é tratado como falha de storage")
    void shouldMapMissingObjectAsStorageFailure() {
        ResponseEntity<ApiErrorResponse> response = handler.handleStorageFailure(
                new DocumentNotFoundInStorageException("legal-cases/1/abc.pdf"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("exceção inesperada vira 500 com mensagem genérica")
    void shouldMapUnexpectedToInternalError() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleUnexpected(new IllegalStateException("detalhe interno sensível"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo(ApiErrorCodes.INTERNAL_ERROR);
        assertThat(response.getBody().message()).isEqualTo("Erro interno ao processar a requisição");
    }
}
