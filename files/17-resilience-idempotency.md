# Prompt 17 — Revisão de resiliência e idempotência ponta a ponta

Leia `docs/00-knowledge-base.md`, seção 11, integralmente.

## Objetivo
Auditar e reforçar todo o sistema construído até aqui (Prompts 01–16) quanto a resiliência e idempotência, fechando lacunas.

## Escopo
1. Revisar todas as integrações externas (LLM, storage, fila, banco) e confirmar que todas têm: timeout explícito, retry com backoff, e circuit breaker onde aplicável (Resilience4j). Adicionar onde estiver faltando.
2. Implementar um mecanismo de expiração/limpeza para `processing_events` antigos (ex.: job agendado ou política de retenção), para a tabela não crescer indefinidamente.
3. Adicionar testes de caos simples: simular indisponibilidade temporária do banco, da fila e do provedor de LLM, verificando que o sistema se recupera sem perda de dados e sem duplicar processamento ao voltar.
4. Revisar todos os endpoints de escrita (`POST`/`PUT`) e confirmar suporte consistente a `Idempotency-Key`.
5. Documentar, em um novo arquivo `docs/resilience-runbook.md`, os pontos de falha conhecidos e como o sistema se comporta em cada um (pode ser em português).

## Restrições
- Não alterar comportamento de negócio já validado — apenas reforçar resiliência.
- Comentários/Javadoc em português.

## Critério de aceite
- Uma queda simulada do provedor de LLM por 30 segundos não perde nenhuma demanda em processamento — todas retomam corretamente após o retorno do serviço.
