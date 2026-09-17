package com.lexflow.application.review;

import com.lexflow.domain.decision.Decision;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de saída das decisões humanas. */
public interface DecisionRepository {

    Decision save(Decision decision);

    Optional<Decision> findById(UUID id);

    /** Decisões de uma demanda, da mais antiga para a mais recente. */
    List<Decision> findByLegalCaseId(UUID legalCaseId);
}
