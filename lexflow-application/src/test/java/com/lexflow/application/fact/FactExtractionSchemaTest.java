package com.lexflow.application.fact;

import static org.assertj.core.api.Assertions.assertThat;

import com.lexflow.domain.legalcase.LegalCaseType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FactExtractionSchemaTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(LegalCaseType.class)
    @DisplayName("todo tipo de demanda tem schema com a parte comum e os campos específicos")
    void shouldBuildSchemaForEveryType(LegalCaseType type) {
        String schema = FactExtractionSchema.forType(type);

        assertThat(FactExtractionSchema.fieldsOf(type)).hasSize(4);
        assertThat(schema)
                .contains("\"parties\"", "\"monetaryValues\"", "\"relevantDates\"", "\"keyClauses\"", "\"specificFacts\"")
                .doesNotContain("%s");
        FactExtractionSchema.fieldsOf(type).keySet().forEach(field -> assertThat(schema).contains("\"" + field + "\""));
        // A saída estruturada do provedor exige additionalProperties: false em todo objeto.
        assertThat(count(schema, "\"type\": \"object\"")).isEqualTo(count(schema, "\"additionalProperties\": false"));
        assertThat(FactExtractionSchema.fieldsGuide(type).lines()).hasSize(4).allMatch(line -> line.startsWith("- "));
    }

    @Test
    @DisplayName("cada tipo pede os seus próprios fatos")
    void shouldAdjustFieldsByType() {
        assertThat(FactExtractionSchema.fieldsOf(LegalCaseType.CONTRACT_SIGNING)).containsKey("penaltyClause");
        assertThat(FactExtractionSchema.fieldsOf(LegalCaseType.SETTLEMENT_PAYMENT)).containsKey("lawsuitNumber");
        assertThat(FactExtractionSchema.forType(LegalCaseType.SUPPLIER_HIRING))
                .contains("supplierTaxId")
                .doesNotContain("penaltyClause");
    }

    private static int count(String text, String fragment) {
        return text.split(java.util.regex.Pattern.quote(fragment), -1).length - 1;
    }
}
