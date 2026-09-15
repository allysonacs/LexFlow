# Prompt 08 — Classificação de demanda e OCR (sem LLM ainda)

Leia `docs/00-knowledge-base.md`, seções 5 e 8.

## Objetivo
Classificar o tipo de demanda e preparar o texto dos documentos para as próximas etapas, usando apenas regras determinísticas — ainda sem chamar nenhum LLM.

## Escopo
1. Se o `caseType` já vier informado na ingestão (Prompt 05) com confiança, usar diretamente. Caso contrário (ou para validação cruzada), implementar uma classificação simples baseada em metadados/palavras-chave do nome do arquivo ou de um campo de descrição informado pelo requisitante.
2. OCR: para documentos que sejam imagem ou PDF escaneado (sem camada de texto), extrair o texto usando Apache Tika (ou Tesseract via wrapper). Para PDFs com texto nativo, extrair diretamente.
3. Persistir o texto extraído associado ao `Document` (nova coluna ou tabela `document_text_content`, adicionar migration Flyway correspondente).
4. Ao concluir, avançar o status do `LegalCase` de `CLASSIFYING` para `EXTRACTING` e, ao final da extração de texto, para o próximo passo do pipeline (mas ainda não `AI_ANALYSIS_IN_PROGRESS` — isso é do Prompt 11, que depende do Prompt 09 primeiro).
5. Testes cobrindo: classificação correta para cada `LegalCaseType`, extração de texto de um PDF nativo e de uma imagem (fixture de teste).

## Restrições
- Nenhuma chamada a LLM neste prompt.
- Comentários/Javadoc em português.

## Critério de aceite
- Dado um conjunto de documentos de teste (fixtures), o sistema classifica corretamente e extrai o texto sem intervenção manual.
