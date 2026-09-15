# Prompt 15 — API de revisão humana e decisão

Leia `docs/00-knowledge-base.md`, seções 1, 6 e 11.

## Objetivo
Expor as respostas da IA ao responsável humano e permitir o registro da decisão final.

## Escopo
1. `GET /api/v1/legal-cases?status=PENDING_HUMAN_REVIEW` — listagem paginada dos casos aguardando revisão.
2. `GET /api/v1/legal-cases/{id}/analysis` — retorna, para cada `question_key` respondida: `answer`, `confidence_score`, `verification_status`, e o **texto completo** dos `cited_chunks` (não só o id — o frontend precisa exibir a fonte).
3. `POST /api/v1/legal-cases/{id}/decisions` — registra a decisão humana (`decisionType`: `APPROVED` | `REJECTED` | `RETURNED_FOR_CORRECTION`, `comments`, `decidedBy`), com suporte a `Idempotency-Key` (mesma abordagem do Prompt 05).
4. Ao registrar a decisão, avançar o `LegalCase` para o status correspondente via o serviço de transição (Prompt 04). Se `RETURNED_FOR_CORRECTION`, o caso deve poder ser reaberto (voltar para `RECEIVED`) quando nova documentação for enviada.
5. Publicar um evento `DecisionRegisteredEvent` (para ser consumido nos Prompts 16 em diante — notificação e auditoria).
6. Testes de integração cobrindo os três tipos de decisão e a idempotência do endpoint.

## Restrições
- Este endpoint não decide nada sozinho — apenas registra a decisão de um humano autenticado (assumir que a autenticação/autorização já existe ou usar um cabeçalho simples `X-User-Id` por ora, documentando a limitação).
- Comentários/Javadoc em português.

## Critério de aceite
- Reenviar a mesma decisão com a mesma `Idempotency-Key` não gera duas linhas em `decisions` nem duas transições de status.
