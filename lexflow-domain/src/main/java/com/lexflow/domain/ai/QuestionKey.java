package com.lexflow.domain.ai;

import com.lexflow.domain.legalcase.LegalCaseType;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Identificador de cada pergunta jurídica respondida pelo sistema (seção 5 da base de conhecimento).
 */
public enum QuestionKey {

    /** "Podemos contratar esse fornecedor?" */
    CAN_HIRE_SUPPLIER,

    /** "Podemos assinar esse contrato?" */
    CAN_SIGN_CONTRACT,

    /** "Podemos pagar esse acordo?" */
    CAN_PAY_SETTLEMENT,

    /** "Essa ação judicial pode ser encerrada?" */
    CAN_CLOSE_LAWSUIT,

    /** "Podemos aceitar essa proposta?" */
    CAN_ACCEPT_PROPOSAL,

    /**
     * "Esse processo tem documentação suficiente?" — respondida primariamente pelo checklist
     * determinístico, não pelo LLM (seção 9).
     */
    HAS_SUFFICIENT_DOCUMENTATION,

    /** "Essa demanda está de acordo com a legislação e com a política da empresa?" */
    COMPLIES_WITH_LAW_AND_POLICY;

    /** Perguntas feitas para qualquer tipo de demanda. */
    private static final Set<QuestionKey> COMMON_QUESTIONS =
            Set.of(HAS_SUFFICIENT_DOCUMENTATION, COMPLIES_WITH_LAW_AND_POLICY);

    /**
     * Perguntas críticas, que passam pela segunda checagem descrita na seção 10, item 4.
     *
     * <p>No Prompt 14 esta lista passa a ser configurável; aqui ela registra o conjunto mínimo
     * definido pela base de conhecimento.
     */
    private static final Set<QuestionKey> CRITICAL_QUESTIONS =
            Set.of(CAN_SIGN_CONTRACT, CAN_PAY_SETTLEMENT, CAN_CLOSE_LAWSUIT);

    /**
     * Perguntas aplicáveis a um tipo de demanda: a pergunta específica do tipo mais as comuns a
     * todos os tipos. A ordem é estável, começando pela pergunta específica.
     */
    public static Set<QuestionKey> applicableTo(LegalCaseType caseType) {
        Set<QuestionKey> questions = new LinkedHashSet<>();
        questions.add(caseType.primaryQuestion());
        questions.addAll(COMMON_QUESTIONS);
        return Collections.unmodifiableSet(questions);
    }

    /** Indica se esta pergunta exige a segunda checagem (self-verification). */
    public boolean isCritical() {
        return CRITICAL_QUESTIONS.contains(this);
    }

    /** Indica se esta pergunta é resolvida por regra determinística, sem chamar o LLM. */
    public boolean isDeterministic() {
        return this == HAS_SUFFICIENT_DOCUMENTATION;
    }
}
