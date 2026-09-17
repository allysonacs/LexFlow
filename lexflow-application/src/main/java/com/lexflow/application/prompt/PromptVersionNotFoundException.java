package com.lexflow.application.prompt;

import com.lexflow.application.exception.ApplicationException;

/**
 * Não há versão ativa do prompt pedido.
 *
 * <p>É falha de configuração, não da demanda: sem prompt registrado não há chamada rastreável, e o
 * processamento não pode seguir.
 */
public class PromptVersionNotFoundException extends ApplicationException {

    public PromptVersionNotFoundException(String promptKey) {
        super("Nenhuma versão ativa do prompt %s em prompt_versions".formatted(promptKey));
    }
}
