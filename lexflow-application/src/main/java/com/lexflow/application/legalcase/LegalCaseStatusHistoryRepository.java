package com.lexflow.application.legalcase;

import java.util.List;
import java.util.UUID;

/**
 * Porta de saída para o histórico de transições de status.
 *
 * <p>Fica no módulo de aplicação por ser um contrato do negócio; a implementação, em cima de JPA,
 * vive em {@code lexflow-infrastructure} e é ligada pelos casos de uso que persistem a demanda
 * (Prompts 05 em diante).
 *
 * <p>O {@link LegalCaseStatusTransitionService} de propósito não depende desta porta: ele apenas
 * valida a transição e monta o registro, ficando livre de qualquer detalhe de persistência.
 */
public interface LegalCaseStatusHistoryRepository {

    /** Grava um registro de transição. O histórico é somente-acréscimo: nada é alterado ou apagado. */
    void save(LegalCaseStatusHistoryEntry entry);

    /** Linha do tempo completa de uma demanda, da mais antiga para a mais recente. */
    List<LegalCaseStatusHistoryEntry> findByLegalCaseId(UUID legalCaseId);
}
