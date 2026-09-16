package com.lexflow.application.legalcase;

import com.lexflow.application.document.DocumentUpload;
import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCaseType;
import java.util.List;
import java.util.Objects;

/**
 * Dados de entrada da ingestão de uma demanda, já convertidos para tipos do domínio.
 *
 * <p>A conversão de texto para enum acontece antes, na borda (ver {@link LegalCaseType#of}), de modo
 * que um valor inválido é recusado sem que o caso de uso chegue a ser chamado.
 *
 * @param idempotencyKey chave enviada pelo cliente no cabeçalho {@code Idempotency-Key}; opcional
 */
public record ReceiveLegalCaseCommand(
        String externalReference,
        LegalCaseType caseType,
        String requester,
        CasePriority priority,
        List<DocumentUpload> documents,
        String idempotencyKey) {

    public ReceiveLegalCaseCommand {
        Objects.requireNonNull(caseType, "caseType é obrigatório");
        if (requester == null || requester.isBlank()) {
            throw new IllegalArgumentException("requester é obrigatório");
        }
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("é obrigatório enviar ao menos um arquivo");
        }
        // Prioridade é opcional no contrato da API; a ausência vira o valor padrão aqui, e não no
        // controller, para que qualquer ponto de entrada assuma o mesmo padrão.
        priority = priority == null ? CasePriority.DEFAULT : priority;
        documents = List.copyOf(documents);
        externalReference = blankToNull(externalReference);
        idempotencyKey = blankToNull(idempotencyKey);
        requester = requester.trim();
    }

    /** Indica se o cliente pediu tratamento idempotente para esta requisição. */
    public boolean hasIdempotencyKey() {
        return idempotencyKey != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
