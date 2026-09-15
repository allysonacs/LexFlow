package com.lexflow.domain.legalcase;

import java.util.EnumMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Implementação padrão da máquina de estados, transcrevendo exatamente o diagrama da seção 4 da base
 * de conhecimento:
 *
 * <pre>
 * RECEIVED → CLASSIFYING → EXTRACTING → AI_ANALYSIS_IN_PROGRESS → PENDING_HUMAN_REVIEW
 *   → APPROVED | REJECTED | RETURNED_FOR_CORRECTION
 *       RETURNED_FOR_CORRECTION → RECEIVED (reenvio de documentação)
 *       APPROVED | REJECTED → CLOSED (terminal)
 * </pre>
 *
 * <p>Qualquer par que não esteja neste mapa é considerado inválido, inclusive a transição de um
 * status para ele mesmo. Alterar o fluxo exige alterar antes a base de conhecimento.
 */
public class LegalCaseStatusTransitionRules implements LegalCaseStatusTransition {

    private static final Map<LegalCaseStatus, Set<LegalCaseStatus>> ALLOWED_TRANSITIONS = buildTransitions();

    @Override
    public Set<LegalCaseStatus> allowedTransitionsFrom(LegalCaseStatus currentStatus) {
        Objects.requireNonNull(currentStatus, "currentStatus não pode ser nulo");
        return ALLOWED_TRANSITIONS.getOrDefault(currentStatus, Set.of());
    }

    @Override
    public boolean isAllowed(LegalCaseStatus currentStatus, LegalCaseStatus targetStatus) {
        Objects.requireNonNull(targetStatus, "targetStatus não pode ser nulo");
        return allowedTransitionsFrom(currentStatus).contains(targetStatus);
    }

    private static Map<LegalCaseStatus, Set<LegalCaseStatus>> buildTransitions() {
        Map<LegalCaseStatus, Set<LegalCaseStatus>> transitions = new EnumMap<>(LegalCaseStatus.class);
        transitions.put(LegalCaseStatus.RECEIVED, Set.of(LegalCaseStatus.CLASSIFYING));
        transitions.put(LegalCaseStatus.CLASSIFYING, Set.of(LegalCaseStatus.EXTRACTING));
        transitions.put(LegalCaseStatus.EXTRACTING, Set.of(LegalCaseStatus.AI_ANALYSIS_IN_PROGRESS));
        transitions.put(LegalCaseStatus.AI_ANALYSIS_IN_PROGRESS, Set.of(LegalCaseStatus.PENDING_HUMAN_REVIEW));
        transitions.put(
                LegalCaseStatus.PENDING_HUMAN_REVIEW,
                Set.of(
                        LegalCaseStatus.APPROVED,
                        LegalCaseStatus.REJECTED,
                        LegalCaseStatus.RETURNED_FOR_CORRECTION));
        transitions.put(LegalCaseStatus.APPROVED, Set.of(LegalCaseStatus.CLOSED));
        transitions.put(LegalCaseStatus.REJECTED, Set.of(LegalCaseStatus.CLOSED));
        transitions.put(LegalCaseStatus.RETURNED_FOR_CORRECTION, Set.of(LegalCaseStatus.RECEIVED));
        transitions.put(LegalCaseStatus.CLOSED, Set.of());
        return Collections.unmodifiableMap(transitions);
    }
}
