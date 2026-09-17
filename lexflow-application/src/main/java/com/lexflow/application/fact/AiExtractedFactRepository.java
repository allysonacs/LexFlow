package com.lexflow.application.fact;

import com.lexflow.domain.ai.AiExtractedFact;
import java.util.List;
import java.util.UUID;

/**
 * Porta de saída dos fatos extraídos.
 *
 * <p>O banco garante no máximo um registro por documento, o que torna a extração retomável.
 */
public interface AiExtractedFactRepository {

    void save(AiExtractedFact fact);

    List<AiExtractedFact> findByLegalCaseId(UUID legalCaseId);
}
