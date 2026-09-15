# Prompt 11 — Extração estruturada de fatos via LLM

Leia `docs/00-knowledge-base.md`, seção 10, item 1.

## Objetivo
Primeiro uso real do LLM no pipeline: extrair fatos do documento em formato estruturado, sem opinião jurídica.

## Escopo
1. Criar o caso de uso `ExtractLegalFactsUseCase` em `lexflow-application`, usando o `LlmClientPort` do Prompt 10.
2. Definir o schema de saída esperado (JSON Schema) para `AiExtractedFact`: partes envolvidas, valores monetários, datas relevantes, cláusulas-chave — ajustável conforme o `LegalCaseType`.
3. Montar o prompt de extração: deve instruir explicitamente o modelo a **apenas extrair fatos presentes no texto**, nunca inferir ou completar informação ausente, e a marcar campos não encontrados como `null`.
4. Persistir o resultado em `ai_extracted_facts`, associado ao `document_id` e `legal_case_id`, com o `model_version` usado.
5. Validar a saída do modelo contra o schema antes de persistir; em caso de falha de validação, acionar retry (uma vez) com um prompt reforçando o formato esperado, e se falhar de novo, marcar o `LegalCase` com um alerta e não avançar automaticamente o pipeline.
6. Testes usando um stub do `LlmClientPort` (não chamar API real em teste automatizado).

## Restrições
- Nenhuma pergunta jurídica é respondida nesta etapa — só extração de fatos.
- Comentários/Javadoc em português.

## Critério de aceite
- Dado um contrato de teste (fixture), os fatos extraídos batem com um gabarito esperado no teste.
- Uma extração malformada do modelo nunca é persistida como se fosse válida.
