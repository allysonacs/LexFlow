package com.lexflow.domain.legalcase;

import com.lexflow.domain.exception.InvalidStatusTransitionException;
import java.util.Set;

/**
 * Máquina de estados de uma demanda jurídica, isolada em uma interface própria para poder ser
 * testada e substituída sem tocar no agregado {@link LegalCase}.
 *
 * <p>A implementação padrão é {@link LegalCaseStatusTransitionRules}.
 */
public interface LegalCaseStatusTransition {

    /** Status para os quais é possível ir a partir de {@code currentStatus}. */
    Set<LegalCaseStatus> allowedTransitionsFrom(LegalCaseStatus currentStatus);

    /** Indica se a transição é permitida, sem lançar exceção. */
    default boolean isAllowed(LegalCaseStatus currentStatus, LegalCaseStatus targetStatus) {
        return allowedTransitionsFrom(currentStatus).contains(targetStatus);
    }

    /**
     * Valida a transição.
     *
     * @throws InvalidStatusTransitionException se a transição não for permitida
     */
    default void validateTransition(LegalCaseStatus currentStatus, LegalCaseStatus targetStatus) {
        if (!isAllowed(currentStatus, targetStatus)) {
            throw new InvalidStatusTransitionException(currentStatus, targetStatus);
        }
    }
}
