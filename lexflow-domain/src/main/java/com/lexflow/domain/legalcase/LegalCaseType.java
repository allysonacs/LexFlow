package com.lexflow.domain.legalcase;

import com.lexflow.domain.ai.QuestionKey;
import com.lexflow.domain.exception.UnknownLegalCaseTypeException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * Converte o texto recebido de um cliente externo no tipo correspondente.
     *
     * <p>Fica no domínio, e não no controller, para que qualquer ponto de entrada rejeite um tipo
     * desconhecido da mesma forma e com a mesma mensagem.
     *
     * @throws UnknownLegalCaseTypeException se o valor não corresponder a nenhum tipo do glossário
     */
    public static LegalCaseType of(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new UnknownLegalCaseTypeException(value, supportedValues()));
    }

    /** Nomes aceitos, na ordem declarada, para compor mensagens de erro. */
    public static Set<String> supportedValues() {
        return Arrays.stream(values())
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
