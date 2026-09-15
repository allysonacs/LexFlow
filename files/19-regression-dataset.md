# Prompt 19 — Dataset de regressão de prompts

Leia `docs/00-knowledge-base.md`, seção 10.

## Objetivo
Criar um mecanismo para validar, de forma repetível, que mudanças de prompt ou de modelo não degradam a qualidade das respostas jurídicas.

## Escopo
1. Criar uma estrutura de "casos de teste dourados" (`golden cases`): um conjunto de demandas fixture (documentos + fatos esperados + respostas corretas conhecidas para cada `question_key`), anonimizadas, armazenadas em `src/test/resources/golden-cases/`.
2. Implementar um `PromptRegressionTestRunner` que roda o pipeline completo (Prompts 11 a 14) contra cada golden case usando o `LlmClientPort` real (não stub) — este runner deve ser executável separadamente da suíte de testes normal (ex.: task Gradle dedicada `./gradlew regressionTest`), pois envolve custo de chamadas reais.
3. Para cada golden case, comparar a resposta gerada com a esperada e calcular métricas simples: percentual de `question_key` corretas, percentual de respostas com `verificationStatus = FAILED`, tempo médio de execução.
4. Gerar um relatório (arquivo `.md` ou `.json`) ao final da execução, comparável entre execuções (para detectar regressão ao trocar de prompt/modelo).
5. Documentar em `docs/prompt-regression.md` como adicionar novos golden cases e como interpretar o relatório.

## Restrições
- Este runner nunca deve rodar automaticamente no pipeline de CI padrão (custo e determinismo), apenas sob demanda ou em pipeline dedicado.
- Comentários/Javadoc em português.

## Critério de aceite
- Executar `./gradlew regressionTest` localmente produz um relatório com os resultados de todos os golden cases cadastrados, permitindo comparar duas execuções lado a lado.
