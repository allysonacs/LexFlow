package com.lexflow.application.analysis;

/**
 * Porta que lê o JSON de uma resposta jurídica já validado contra {@link LegalAnalysisSchema}.
 *
 * <p>Existe porque {@code lexflow-application} não conhece biblioteca de serialização (seção 7): a
 * leitura do JSON é detalhe de infraestrutura, e o caso de uso trabalha com {@link LegalAnalysisAnswer}.
 */
public interface LegalAnalysisAnswerReader {

    /**
     * Lê o JSON da resposta.
     *
     * @throws IllegalArgumentException se o texto não for um JSON no formato do schema — o que só
     *     acontece se a validação anterior tiver sido pulada
     */
    LegalAnalysisAnswer read(String json);
}
