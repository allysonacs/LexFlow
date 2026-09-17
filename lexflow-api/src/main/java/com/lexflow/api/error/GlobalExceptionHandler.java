package com.lexflow.api.error;

import com.lexflow.application.checklist.ChecklistRuleInUseException;
import com.lexflow.application.checklist.DuplicateChecklistRuleException;
import com.lexflow.application.exception.DocumentStorageException;
import com.lexflow.application.exception.IdempotentRequestInProgressException;
import com.lexflow.application.knowledge.EmbeddingUnavailableException;
import com.lexflow.domain.exception.ChecklistRuleNotFoundException;
import com.lexflow.domain.exception.DomainException;
import com.lexflow.domain.exception.InvalidStatusTransitionException;
import com.lexflow.domain.exception.KnowledgeBaseSourceNotFoundException;
import com.lexflow.domain.exception.LegalCaseNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Tratamento padronizado de erros de toda a API.
 *
 * <p>Concentrar a tradução aqui é o que permite que controllers e casos de uso lancem exceções
 * descritivas — {@code UnsupportedDocumentFormatException}, {@code LegalCaseNotFoundException} — sem
 * conhecer código HTTP nenhum. A escolha do status fica em um lugar só.
 *
 * <p>Erros de negócio ({@link DomainException}) viram 4xx: são culpa da requisição, e a mensagem é
 * devolvida ao cliente porque ela o ajuda a corrigir o envio. Qualquer outra exceção vira 500 com
 * mensagem genérica — o detalhe vai para o log, nunca para a resposta, para não expor a estrutura
 * interna do sistema.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    /** Demanda, regra de checklist ou fonte normativa inexistente. */
    @ExceptionHandler({
        LegalCaseNotFoundException.class,
        ChecklistRuleNotFoundException.class,
        KnowledgeBaseSourceNotFoundException.class
    })
    public ResponseEntity<ApiErrorResponse> handleNotFound(DomainException e, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ApiErrorCodes.RESOURCE_NOT_FOUND, e.getMessage(), request);
    }

    /** Transição de status recusada pela máquina de estados (seção 4). */
    @ExceptionHandler(InvalidStatusTransitionException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidTransition(
            InvalidStatusTransitionException e, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ApiErrorCodes.CONFLICT, e.getMessage(), request);
    }

    /**
     * Conflitos com o estado atual: chave de idempotência reservada por uma ingestão que ainda não
     * terminou, regra de checklist duplicada ou regra em uso que não pode ser excluída.
     */
    @ExceptionHandler({
        IdempotentRequestInProgressException.class,
        DuplicateChecklistRuleException.class,
        ChecklistRuleInUseException.class
    })
    public ResponseEntity<ApiErrorResponse> handleConflict(RuntimeException e, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ApiErrorCodes.CONFLICT, e.getMessage(), request);
    }

    /**
     * Falha do storage de documentos.
     *
     * <p>Vira 503, e não 500, porque a causa é uma dependência externa e não um defeito da
     * requisição: o cliente pode tentar de novo, de preferência com a mesma {@code Idempotency-Key}.
     * A mensagem devolvida é genérica — endereço de bucket e detalhe do SDK ficam no log.
     */
    @ExceptionHandler(DocumentStorageException.class)
    public ResponseEntity<ApiErrorResponse> handleStorageFailure(
            DocumentStorageException e, HttpServletRequest request) {
        log.error("Falha no storage de documentos em {} {}", request.getMethod(), request.getRequestURI(), e);
        return build(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCodes.STORAGE_UNAVAILABLE,
                "Storage de documentos indisponível; tente novamente",
                request);
    }

    /**
     * Provedor de embeddings indisponível (Prompt 12).
     *
     * <p>Vira 503 pelo mesmo motivo do storage: a causa é uma dependência externa, e a indexação pode
     * ser reenviada quando o provedor voltar. O detalhe fica no log.
     */
    @ExceptionHandler(EmbeddingUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleEmbeddingUnavailable(
            EmbeddingUnavailableException e, HttpServletRequest request) {
        log.error("Falha no provedor de embeddings em {} {}", request.getMethod(), request.getRequestURI(), e);
        return build(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCodes.EMBEDDINGS_UNAVAILABLE,
                "Provedor de embeddings indisponível; tente novamente",
                request);
    }

    /**
     * Demais violações de regra de negócio: tipo de demanda desconhecido, formato de arquivo recusado,
     * prioridade inválida. Todas descrevem um dado errado na requisição, logo 400.
     */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiErrorResponse> handleDomain(DomainException e, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ApiErrorCodes.INVALID_REQUEST, e.getMessage(), request);
    }

    /** Validações feitas com {@code IllegalArgumentException} pelos records de domínio e de comando. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException e, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ApiErrorCodes.INVALID_REQUEST, e.getMessage(), request);
    }

    /** Corpo JSON malformado ou com valor que não converte, como um {@code caseType} desconhecido. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException e, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                ApiErrorCodes.INVALID_REQUEST,
                "Corpo da requisição inválido ou malformado",
                request);
    }

    /** Campo obrigatório ausente, arquivo não enviado ou valor que não converte para o tipo esperado. */
    @ExceptionHandler({
        MissingServletRequestParameterException.class,
        MissingServletRequestPartException.class,
        MethodArgumentTypeMismatchException.class,
        MethodArgumentNotValidException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception e, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ApiErrorCodes.INVALID_REQUEST, e.getMessage(), request);
    }

    /** Upload acima do limite configurado em {@code spring.servlet.multipart}. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleTooLarge(
            MaxUploadSizeExceededException e, HttpServletRequest request) {
        return build(
                HttpStatus.PAYLOAD_TOO_LARGE,
                ApiErrorCodes.PAYLOAD_TOO_LARGE,
                "Arquivo maior do que o limite aceito pela API",
                request);
    }

    /** Rede de segurança: o que chega aqui é defeito nosso, não do cliente. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Falha não tratada em {} {}", request.getMethod(), request.getRequestURI(), e);
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiErrorCodes.INTERNAL_ERROR,
                "Erro interno ao processar a requisição",
                request);
    }

    private ResponseEntity<ApiErrorResponse> build(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(code, message, clock.instant(), request.getRequestURI()));
    }
}
