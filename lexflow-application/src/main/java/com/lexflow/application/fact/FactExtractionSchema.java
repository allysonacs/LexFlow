package com.lexflow.application.fact;

import com.lexflow.domain.legalcase.LegalCaseType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * JSON Schema dos fatos extraídos, ajustado a cada tipo de demanda.
 *
 * <p>A parte comum vale para todo documento:
 *
 * <ul>
 *   <li>{@code documentKind}: como o documento se identifica (título);
 *   <li>{@code parties}: partes, com papel e CPF/CNPJ;
 *   <li>{@code monetaryValues}: valores, como aparecem no texto e, quando completos, em número;
 *   <li>{@code relevantDates}: datas, como aparecem e, quando completas, em ISO 8601;
 *   <li>{@code keyClauses}: cláusulas-chave, com um trecho literal.
 * </ul>
 *
 * <p>{@code specificFacts} traz os campos próprios do tipo de demanda. Todo campo é obrigatório e
 * aceita {@code null}: a ausência de um dado fica explícita, e o modelo não tem como omitir um campo
 * nem inventar outro ({@code additionalProperties: false} em todos os objetos, como a saída
 * estruturada do provedor exige).
 *
 * <p><strong>Mudar este schema é mudar o contrato da extração:</strong> o prompt versionado
 * ({@code FACT_EXTRACTION}) descreve os mesmos campos, e uma alteração aqui pede uma nova versão dele.
 */
public final class FactExtractionSchema {

    private static final Map<LegalCaseType, Map<String, String>> SPECIFIC_FIELDS = specificFields();

    private static final String COMMON_TEMPLATE = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["documentKind", "parties", "monetaryValues", "relevantDates", "keyClauses", "specificFacts"],
              "properties": {
                "documentKind": {
                  "type": ["string", "null"],
                  "description": "Como o documento se identifica, normalmente pelo título"
                },
                "parties": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["name", "role", "taxId"],
                    "properties": {
                      "name": {"type": "string", "description": "Nome da parte como aparece no texto"},
                      "role": {"type": ["string", "null"], "description": "Papel no documento (ex.: contratante)"},
                      "taxId": {"type": ["string", "null"], "description": "CPF ou CNPJ como aparece no texto"}
                    }
                  }
                },
                "monetaryValues": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["description", "amountText", "amount", "currency"],
                    "properties": {
                      "description": {"type": "string", "description": "A que o valor se refere"},
                      "amountText": {"type": "string", "description": "Valor exatamente como aparece no texto"},
                      "amount": {"type": ["number", "null"], "description": "Valor numérico, só quando completo no texto"},
                      "currency": {"type": ["string", "null"], "description": "Código ISO 4217 (ex.: BRL), só quando indicado no texto"}
                    }
                  }
                },
                "relevantDates": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["description", "dateText", "isoDate"],
                    "properties": {
                      "description": {"type": "string", "description": "A que a data ou o prazo se refere"},
                      "dateText": {"type": "string", "description": "Data ou prazo exatamente como aparece no texto"},
                      "isoDate": {"type": ["string", "null"], "format": "date", "description": "AAAA-MM-DD, só quando a data está completa no texto"}
                    }
                  }
                },
                "keyClauses": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["title", "excerpt"],
                    "properties": {
                      "title": {"type": ["string", "null"], "description": "Título ou número da cláusula"},
                      "excerpt": {"type": "string", "description": "Trecho literal curto da cláusula"}
                    }
                  }
                },
                "specificFacts": %s
              }
            }
            """;

    private FactExtractionSchema() {
        // classe utilitária
    }

    /** Schema completo, em JSON, para o tipo de demanda. */
    public static String forType(LegalCaseType caseType) {
        Map<String, String> fields = fieldsOf(caseType);
        String properties = fields.entrySet().stream()
                .map(field -> "\"%s\": {\"type\": [\"string\", \"null\"], \"description\": \"%s\"}"
                        .formatted(field.getKey(), field.getValue()))
                .collect(Collectors.joining(", "));
        String required = fields.keySet().stream()
                .map(name -> "\"" + name + "\"")
                .collect(Collectors.joining(", "));
        String specificFacts = """
                {"type": "object", "additionalProperties": false, "required": [%s], "properties": {%s}}"""
                .formatted(required, properties);
        return COMMON_TEMPLATE.formatted(specificFacts);
    }

    /** Campos de {@code specificFacts} do tipo, com a descrição de cada um, na ordem do schema. */
    public static Map<String, String> fieldsOf(LegalCaseType caseType) {
        return SPECIFIC_FIELDS.get(Objects.requireNonNull(caseType, "caseType não pode ser nulo"));
    }

    /** Descrição legível dos campos específicos, usada no prompt. */
    public static String fieldsGuide(LegalCaseType caseType) {
        return fieldsOf(caseType).entrySet().stream()
                .map(field -> "- %s: %s".formatted(field.getKey(), field.getValue()))
                .collect(Collectors.joining("\n"));
    }

    private static Map<LegalCaseType, Map<String, String>> specificFields() {
        Map<LegalCaseType, Map<String, String>> fields = new EnumMap<>(LegalCaseType.class);
        fields.put(LegalCaseType.SUPPLIER_HIRING, ordered(
                "supplierName", "Nome do fornecedor",
                "supplierTaxId", "CNPJ ou CPF do fornecedor",
                "serviceScope", "Objeto ou escopo do fornecimento",
                "contractTermText", "Prazo de vigência, como aparece no texto"));
        fields.put(LegalCaseType.CONTRACT_SIGNING, ordered(
                "contractObject", "Objeto do contrato",
                "contractTermText", "Prazo de vigência, como aparece no texto",
                "terminationConditions", "Condições de rescisão, como aparecem no texto",
                "penaltyClause", "Multas ou penalidades previstas, como aparecem no texto"));
        fields.put(LegalCaseType.SETTLEMENT_PAYMENT, ordered(
                "lawsuitNumber", "Número do processo relacionado ao acordo",
                "settlementAmountText", "Valor total do acordo, como aparece no texto",
                "installmentsText", "Forma de pagamento ou parcelamento, como aparece no texto",
                "paymentDeadlineText", "Prazo de pagamento, como aparece no texto"));
        fields.put(LegalCaseType.LAWSUIT_CLOSURE, ordered(
                "lawsuitNumber", "Número do processo",
                "court", "Vara ou tribunal",
                "closureRequestText", "Pedido de encerramento ou arquivamento, como aparece no texto",
                "pendingObligationsText", "Obrigações ainda pendentes mencionadas no texto"));
        fields.put(LegalCaseType.PROPOSAL_ACCEPTANCE, ordered(
                "proposingParty", "Quem apresenta a proposta",
                "proposalObject", "Objeto da proposta",
                "proposedAmountText", "Valor proposto, como aparece no texto",
                "validityText", "Validade da proposta, como aparece no texto"));
        return Collections.unmodifiableMap(fields);
    }

    private static Map<String, String> ordered(String... nameAndDescription) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 0; index < nameAndDescription.length; index += 2) {
            fields.put(nameAndDescription[index], nameAndDescription[index + 1]);
        }
        return Collections.unmodifiableMap(fields);
    }
}
