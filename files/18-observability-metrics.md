# Prompt 18 — Observabilidade e métricas de qualidade da IA

Leia `docs/00-knowledge-base.md`, seções 10 e 11.

## Objetivo
Instrumentar o sistema para permitir monitorar sua saúde operacional e a qualidade das respostas da IA ao longo do tempo.

## Escopo
1. Configurar Micrometer + OpenTelemetry: tracing distribuído cobrindo o fluxo completo (ingestão → fila → classificação → IA → revisão), com correlação por `legal_case_id`.
2. Métricas técnicas: latência e taxa de erro de cada integração externa (LLM, storage, fila), tamanho da fila, tempo médio de processamento por `LegalCaseType`.
3. **Métrica de negócio central**: taxa de concordância entre a sugestão implícita da IA (ex.: quando todas as respostas são positivas e sem alerta) e a decisão final do humano. Modelar isso como um evento/tabela simples (`ai_human_agreement_log` ou visão SQL sobre `ai_analysis_responses` + `decisions`) e expor via endpoint ou dashboard.
4. Logs estruturados (JSON) em todos os módulos, com `legal_case_id` como campo de correlação obrigatório.
5. Dashboard básico (pode ser um endpoint que retorna os dados agregados em JSON, para consumo por Grafana/Metabase futuramente — não é necessário implementar o dashboard visual em si).

## Restrições
- Nunca incluir conteúdo de documento ou prompt completo em métricas ou traces — apenas metadados.
- Comentários/Javadoc em português.

## Critério de aceite
- É possível, a partir de um `legal_case_id`, rastrear o caminho completo do caso em um único trace distribuído.
- A métrica de concordância IA-humano é calculável para um conjunto de casos de teste.
