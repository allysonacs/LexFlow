package com.lexflow.application.legalcase;

import com.lexflow.domain.legalcase.LegalCase;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída do agregado {@link LegalCase}.
 *
 * <p>Trafega o agregado de domínio, não a entidade JPA: é o adapter em {@code lexflow-infrastructure}
 * que conhece o banco.
 */
public interface LegalCaseRepository {

    /** Grava a demanda, criando ou atualizando a linha correspondente. */
    LegalCase save(LegalCase legalCase);

    Optional<LegalCase> findById(UUID id);
}
