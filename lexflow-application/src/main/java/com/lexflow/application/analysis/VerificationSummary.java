package com.lexflow.application.analysis;

/**
 * O que a segunda checagem fez em uma demanda (Prompt 14).
 *
 * @param verified respostas confirmadas pelos trechos citados
 * @param failed respostas não confirmadas: confiança zerada e atenção redobrada do revisor
 * @param inconclusive respostas cuja verificação não pôde ser concluída; seguem como não verificadas
 * @param llmCalls chamadas de verificação feitas nesta execução
 */
public record VerificationSummary(int verified, int failed, int inconclusive, int llmCalls) {

    /** Nenhuma verificação feita — a etapa está desligada ou não havia pergunta crítica pendente. */
    public static final VerificationSummary NONE = new VerificationSummary(0, 0, 0, 0);

    public int checked() {
        return verified + failed + inconclusive;
    }
}
