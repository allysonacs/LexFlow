package com.lexflow.application.prompt;

import com.lexflow.domain.ai.PromptVersion;
import java.util.Optional;

/** Porta de saída das versões de prompt. */
public interface PromptVersionRepository {

    /** Versão ativa de um prompt. O banco garante no máximo uma por chave. */
    Optional<PromptVersion> findActive(String promptKey);
}
