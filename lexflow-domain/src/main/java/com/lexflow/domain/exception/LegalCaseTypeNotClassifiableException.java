package com.lexflow.domain.exception;

import com.lexflow.domain.legalcase.LegalCaseType;
import java.util.Set;

/**
 * Lançada quando uma demanda chega sem tipo informado e as palavras-chave não permitem deduzir um
 * tipo único — seja por falta de evidência, seja por empate.
 */
public class LegalCaseTypeNotClassifiableException extends DomainException {

    private final Set<LegalCaseType> tiedTypes;

    public LegalCaseTypeNotClassifiableException(Set<LegalCaseType> tiedTypes) {
        super(tiedTypes.isEmpty()
                ? "Não foi possível classificar a demanda: nenhuma palavra-chave encontrada"
                : "Não foi possível classificar a demanda: empate entre %s".formatted(tiedTypes));
        this.tiedTypes = Set.copyOf(tiedTypes);
    }

    /** Tipos empatados; vazio quando não houve evidência nenhuma. */
    public Set<LegalCaseType> tiedTypes() {
        return tiedTypes;
    }
}
