package com.lexflow.domain.ai;

import com.lexflow.domain.legalcase.LegalCaseType;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Identificador de cada pergunta jurídica respondida pelo sistema (seção 5 da base de conhecimento).
 */
public enum QuestionKey {

    /** "Podemos contratar esse fornecedor?" */
    CAN_HIRE_SUPPLIER("Podemos contratar esse fornecedor?"),

    /** "Podemos assinar esse contrato?" */
    CAN_SIGN_CONTRACT("Podemos assinar esse contrato?"),

    /** "Podemos pagar esse acordo?" */
    CAN_PAY_SETTLEMENT("Podemos pagar esse acordo?"),

    /** "Essa ação judicial pode ser encerrada?" */
    CAN_CLOSE_LAWSUIT("Essa ação judicial pode ser encerrada?"),

    /** "Podemos aceitar essa proposta?" */
    CAN_ACCEPT_PROPOSAL("Podemos aceitar essa proposta?"),

    /**
     * "Esse processo tem documentação suficiente?" — respondida primariamente pelo checklist
     * determinístico, não pelo LLM (seção 9).
     */
    HAS_SUFFICIENT_DOCUMENTATION("Esse processo tem documentação suficiente?"),

    /** "Essa demanda está de acordo com a legislação e com a política da empresa?" */
    COMPLIES_WITH_LAW_AND_POLICY(
            "Essa demanda está de acordo com a legislação e com a política da empresa?");

    /**
     * Enunciado da pergunta em português, como ela é feita pela área jurídica (seção 5).
     *
     * <p>Fica no domínio, e não no texto do prompt, porque é a mesma pergunta que o revisor humano lê
     * na tela: um enunciado e outro não podem divergir.
     */
    private final String statement;

    QuestionKey(String statement) {
        this.statement = statement;
    }

    /** Enunciado em português desta pergunta. */
    public String statement() {
        return statement;
    }

    /**
     * Perguntas feitas para qualquer tipo de demanda.
     *
     * <p>Usa {@link LinkedHashSet}, e não {@code Set.of}, porque a ordem faz parte do contrato de
     * {@link #applicableTo}: a ordem de iteração de um {@code Set.of} varia a cada execução da JVM, o
     * que tornaria instável tanto a apresentação das perguntas quanto os testes.
     */
    private static final Set<QuestionKey> COMMON_QUESTIONS = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(HAS_SUFFICIENT_DOCUMENTATION, COMPLIES_WITH_LAW_AND_POLICY)));

    /**
     * Perguntas críticas, que passam pela segunda checagem descrita na seção 10, item 4.
     *
     * <p>A configuração pode ampliar esta lista (Prompt 14); aqui fica o conjunto mínimo definido
     * pela base de conhecimento.
     */
    private static final Set<QuestionKey> CRITICAL_QUESTIONS = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(CAN_SIGN_CONTRACT, CAN_PAY_SETTLEMENT, CAN_CLOSE_LAWSUIT)));

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

    /**
     * Conjunto mínimo de perguntas críticas definido pela base de conhecimento.
     *
     * <p>A configuração pode ampliá-lo (Prompt 14), nunca reduzi-lo abaixo do que a seção 10, item 4,
     * exige: as três perguntas cuja resposta errada tem a consequência mais cara.
     */
    public static Set<QuestionKey> criticalQuestions() {
        return CRITICAL_QUESTIONS;
    }

    /** Indica se esta pergunta é resolvida por regra determinística, sem chamar o LLM. */
    public boolean isDeterministic() {
        return this == HAS_SUFFICIENT_DOCUMENTATION;
    }
}
