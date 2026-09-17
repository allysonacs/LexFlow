package com.lexflow.api.regression;

import java.util.List;
import java.util.Map;

/**
 * Um caso de teste dourado: uma demanda fictícia com o que se espera que a IA responda sobre ela.
 *
 * <p><strong>O gabarito não é o texto da resposta.</strong> Comparar texto gerado com texto esperado
 * reprovaria qualquer variação de redação — e aprovaria uma resposta com a redação certa e a
 * conclusão errada. O que o golden case declara são as propriedades que a resposta precisa ter:
 * estar fundamentada, citar a norma certa, mencionar o que importa, não mencionar o que não existe e
 * não desabar de confiança.
 *
 * @param knowledgeSources normas indexadas antes de a demanda ser analisada; o caso é
 *     autossuficiente, e não depende do que já houver na base
 * @param documents documentos da demanda, com o gabarito parcial dos fatos de cada um
 */
public record GoldenCase(
        String id,
        String description,
        String caseType,
        String requester,
        List<KnowledgeSource> knowledgeSources,
        List<GoldenDocument> documents,
        List<ExpectedAnswer> expectedAnswers) {

    /** Norma indexada para o caso, lida de um arquivo ao lado do {@code case.json}. */
    public record KnowledgeSource(String title, String sourceType, String file) {}

    /**
     * Documento da demanda.
     *
     * @param fixture nome do arquivo em {@code fixtures/documents}, compartilhado com os demais testes
     * @param expectedFacts subconjunto do JSON de fatos que precisa aparecer na extração; campos não
     *     declarados simplesmente não são conferidos
     */
    public record GoldenDocument(String fixture, String documentType, Map<String, Object> expectedFacts) {}

    /**
     * O que se espera da resposta a uma pergunta.
     *
     * @param stance {@code GROUNDED} (fundamentada em trecho citado), {@code NOT_FOUND} (declara que
     *     a base não tem o assunto) ou {@code DETERMINISTIC} (resolvida por regra de código)
     * @param mustMention termos que precisam aparecer na resposta
     * @param mustNotMention termos que não podem aparecer — é aqui que se prendem alucinações
     *     conhecidas, como um valor ou uma lei que o documento não menciona
     * @param mustCiteSource título da fonte normativa que precisa estar entre as citadas
     * @param minConfidence confiança mínima aceitável
     * @param expectedVerification {@code VERIFIED}, {@code FAILED} ou {@code NOT_VERIFIED}; nulo não
     *     confere
     */
    public record ExpectedAnswer(
            String questionKey,
            String stance,
            List<String> mustMention,
            List<String> mustNotMention,
            String mustCiteSource,
            Double minConfidence,
            String expectedVerification) {

        public List<String> mustMentionOrEmpty() {
            return mustMention == null ? List.of() : mustMention;
        }

        public List<String> mustNotMentionOrEmpty() {
            return mustNotMention == null ? List.of() : mustNotMention;
        }
    }
}
