# Prompt 16 — Log de auditoria imutável

Leia `docs/00-knowledge-base.md`, seções 6 e 12.

## Objetivo
Garantir rastreabilidade completa de todas as ações relevantes do sistema.

## Escopo
1. Implementar `AuditLogWriter` (porta + adapter) que grava em `audit_logs` para, no mínimo: criação de `LegalCase`, cada transição de status, cada `AiAnalysisResponse` persistida, cada `Decision` registrada.
2. Cada linha de auditoria deve conter: `entity_type`, `entity_id`, `action`, `actor` (usuário ou `SYSTEM`/`AI`), `payload` (jsonb com o que mudou) e `occurred_at`.
3. Garantir, a nível de banco, que a tabela `audit_logs` não permite `UPDATE` nem `DELETE` (trigger ou permissão de banco restringindo essas operações para o usuário da aplicação).
4. `GET /api/v1/legal-cases/{id}/audit-log` — retorna a linha do tempo completa de um caso.
5. Testes garantindo que uma tentativa de alterar ou apagar uma linha de auditoria via a aplicação falha.

## Restrições
- Nenhuma lógica de negócio deve depender do log de auditoria para funcionar (ele é observador, não participante do fluxo).
- Comentários/Javadoc em português.

## Critério de aceite
- A timeline retornada por `GET /api/v1/legal-cases/{id}/audit-log` reconstrói fielmente a história de um caso de teste ponta a ponta (ingestão → IA → decisão).
