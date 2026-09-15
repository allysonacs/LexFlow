# Prompt 07 — Fila e orquestração assíncrona

Leia `docs/00-knowledge-base.md`, seções 7, 8 e 11 (idempotência e resiliência).

## Objetivo
Desacoplar a ingestão do processamento, publicando e consumindo eventos de forma assíncrona e idempotente. Decida entre Kafka e RabbitMQ e justifique a escolha em um comentário no topo da classe de configuração (para um volume de "milhares de eventos por dia com múltiplos consumidores", ambos servem — escolha com base em simplicidade operacional se não houver preferência explícita do time).

## Escopo
1. Definir o evento `LegalCaseReceivedEvent` (payload mínimo: `legalCaseId`, `idempotencyKey`, `occurredAt`).
2. Implementar `LegalCaseReceivedEventPublisher` (porta definida no Prompt 05) publicando o evento após a ingestão bem-sucedida.
3. Implementar o consumer correspondente:
   - Antes de processar, verificar em `processing_events` se a `idempotency_key` já foi processada; se sim, logar e ignorar (não é erro).
   - Se não, registrar o evento como `IN_PROGRESS`, processar (nesta etapa, apenas avançar o status para `CLASSIFYING` via o serviço do Prompt 04 — a classificação real vem no Prompt 08), e marcar como `PROCESSED`.
   - Em caso de falha, não marcar como processado, permitindo reprocessamento/retry.
4. Configurar retry com backoff exponencial no consumo (ex.: Spring Retry ou mecanismo nativo do broker escolhido) e uma dead-letter queue/topic para mensagens que falham repetidamente.
5. Testes de integração com Testcontainers do broker escolhido, cobrindo: processamento normal, mensagem duplicada (idempotência), e falha simulada indo para a dead-letter.

## Restrições
- Nenhuma lógica de negócio de classificação real ainda — isso é do Prompt 08.
- Comentários/Javadoc em português.

## Critério de aceite
- Publicar duas vezes o mesmo evento (mesma `idempotencyKey`) resulta em processamento único.
- Uma falha forçada no consumer não trava o processamento de outras mensagens da fila.
