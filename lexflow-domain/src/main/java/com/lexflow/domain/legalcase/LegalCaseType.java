package com.lexflow.domain.legalcase;

import com.lexflow.domain.ai.QuestionKey;

/**
 * Tipo de demanda jurídica (seção 5 da base de conhecimento).
 *
 * <p>Cada tipo tem uma pergunta jurídica principal associada. Um novo tipo só pode ser adicionado
 * aqui depois de ser registrado no glossário da base de conhecimento.
 */
public enum LegalCaseType {

    /** "Podemos contratar esse fornecedor?" */
    SUPPLIER_HIRING(QuestionKey.CAN_HIRE_SUPPLIER),

    /** "Podemos assinar esse contrato?" */
    CONTRACT_SIGNING(QuestionKey.CAN_SIGN_CONTRACT),

    /** "Podemos pagar esse acordo?" */
    SETTLEMENT_PAYMENT(QuestionKey.CAN_PAY_SETTLEMENT),

    /** "Essa ação judicial pode ser encerrada?" */
    LAWSUIT_CLOSURE(QuestionKey.CAN_CLOSE_LAWSUIT),

    /** "Podemos aceitar essa proposta?" */
    PROPOSAL_ACCEPTANCE(QuestionKey.CAN_ACCEPT_PROPOSAL);

    private final QuestionKey primaryQuestion;

    LegalCaseType(QuestionKey primaryQuestion) {
        this.primaryQuestion = primaryQuestion;
    }

    /** Pergunta jurídica específica deste tipo de demanda. */
    public QuestionKey primaryQuestion() {
        return primaryQuestion;
    }
}
