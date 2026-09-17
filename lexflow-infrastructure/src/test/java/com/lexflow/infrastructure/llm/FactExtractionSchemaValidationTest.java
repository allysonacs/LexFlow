package com.lexflow.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lexflow.application.fact.FactExtractionSchema;
import com.lexflow.domain.legalcase.LegalCaseType;
import com.lexflow.infrastructure.testsupport.DocumentFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * O schema da extração de fatos contra o validador real: o gabarito é válido, e as falhas típicas de
 * um modelo — campo a mais, campo faltando, tipo errado — são apanhadas.
 */
class FactExtractionSchemaValidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonSchemaResponseValidator validator = new JsonSchemaResponseValidator(objectMapper);
    private final String contractSchema = FactExtractionSchema.forType(LegalCaseType.CONTRACT_SIGNING);

    @ParameterizedTest(name = "{0}")
    @EnumSource(LegalCaseType.class)
    @DisplayName("o schema de cada tipo é JSON Schema válido e aceita uma extração sem fatos")
    void shouldAcceptEmptyExtractionForEveryType(LegalCaseType type) throws Exception {
        String schema = FactExtractionSchema.forType(type);
        ObjectNode specific = objectMapper.createObjectNode();
        FactExtractionSchema.fieldsOf(type).keySet().forEach(specific::putNull);
        ObjectNode empty = objectMapper.createObjectNode();
        empty.putNull("documentKind");
        empty.putArray("parties");
        empty.putArray("monetaryValues");
        empty.putArray("relevantDates");
        empty.putArray("keyClauses");
        empty.set("specificFacts", specific);

        assertThat(validator.parseSchema(schema).path("type").asText()).isEqualTo("object");
        assertThat(validator.violations(schema, objectMapper.writeValueAsString(empty))).isEmpty();
    }

    @Test
    @DisplayName("o gabarito do contrato de teste segue o schema de assinatura de contrato")
    void answerKeyShouldMatchSchema() {
        String answerKey = DocumentFixtures.readFactsAnswerKey(DocumentFixtures.CONTRACT_FACTS_ANSWER_KEY);

        assertThat(validator.violations(contractSchema, answerKey)).isEmpty();
    }

    @Test
    @DisplayName("campo inventado, campo ausente e tipo errado são apanhados")
    void shouldCatchTypicalModelMistakes() throws Exception {
        ObjectNode facts = (ObjectNode) objectMapper.readTree(
                DocumentFixtures.readFactsAnswerKey(DocumentFixtures.CONTRACT_FACTS_ANSWER_KEY));
        facts.put("legalOpinion", "O contrato pode ser assinado");
        facts.remove("keyClauses");
        ((ObjectNode) facts.withArray("monetaryValues").get(0)).put("amount", "quinze mil");

        assertThat(validator.violations(contractSchema, objectMapper.writeValueAsString(facts)))
                .anySatisfy(violation -> assertThat(violation).contains("legalOpinion"))
                .anySatisfy(violation -> assertThat(violation).contains("keyClauses"))
                .anySatisfy(violation -> assertThat(violation).contains("amount"));
    }

    @Test
    @DisplayName("os campos específicos de outro tipo não passam")
    void shouldRejectFieldsOfAnotherType() {
        String answerKey = DocumentFixtures.readFactsAnswerKey(DocumentFixtures.CONTRACT_FACTS_ANSWER_KEY);

        assertThat(validator.violations(FactExtractionSchema.forType(LegalCaseType.SETTLEMENT_PAYMENT), answerKey))
                .isNotEmpty();
    }
}
