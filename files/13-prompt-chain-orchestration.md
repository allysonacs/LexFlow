# Prompt 13 — Orquestração do prompt chain (resposta às perguntas jurídicas)

Leia `docs/00-knowledge-base.md`, seções 5 e 10 por completo — este é o componente mais crítico do sistema.

## Objetivo
Juntar extração de fatos (Prompt 11) e recuperação normativa (Prompt 12) para responder, de forma estruturada e rastreável, cada `question_key` aplicável ao `LegalCaseType` da demanda.

## Escopo
1. Criar `AnalyzeLegalCaseUseCase` em `lexflow-application`, orquestrando, para cada `question_key` aplicável:
   a. Recuperar os `AiExtractedFact` da demanda.
   b. Recuperar os `KnowledgeBaseChunk` relevantes via `KnowledgeBaseRetriever`.
   c. Montar o prompt final, incluindo explicitamente: os fatos extraídos, os trechos normativos recuperados (com identificador de cada chunk), e a instrução de responder **apenas com base nesse contexto**, citando os `chunk_id` usados.
   d. Chamar o `LlmClientPort`, validar contra o schema de `AiAnalysisResponse` (seção 10).
   e. Persistir a resposta em `ai_analysis_responses`, com o `prompt_version_id` usado (ver Prompt 03 para a tabela `prompt_versions` — criar um registro de versão de prompt aqui se ainda não existir).
2. Após responder todas as perguntas aplicáveis, avançar o `LegalCase` para `PENDING_HUMAN_REVIEW` via o serviço de transição (Prompt 04).
3. Regra de negócio: `HAS_SUFFICIENT_DOCUMENTATION` só aciona o LLM se o checklist determinístico (Prompt 09) já indicar que a documentação está completa — caso contrário, a resposta é gerada automaticamente como "documentação incompleta" sem chamar o LLM.
4. Testes cobrindo o fluxo completo com stubs de `LlmClientPort` e `KnowledgeBaseRetriever`, garantindo que toda resposta persistida tem `cited_chunks` não vazio (exceto quando explicitamente "não encontrado na base normativa").

## Restrições
- Nunca persistir uma resposta sem `prompt_version_id` e `model_version` preenchidos — rastreabilidade é obrigatória.
- Comentários/Javadoc em português.

## Critério de aceite
- Rodando o pipeline completo com um caso de teste fixture, todas as `question_key` aplicáveis ao `LegalCaseType` recebem resposta persistida e o caso avança para `PENDING_HUMAN_REVIEW`.
