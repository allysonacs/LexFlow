# Prompt 04 — Serviço de máquina de estados isolado

Leia `docs/00-knowledge-base.md`, seção 4.

## Objetivo
Extrair a lógica de transição de status para um serviço de aplicação isolado e totalmente testável, desacoplado de controllers ou repositórios, para ser reutilizado por toda a orquestração futura.

## Escopo
1. No módulo `lexflow-application`, criar `LegalCaseStatusTransitionService` (ou nome equivalente) que:
   - Recebe o `LegalCase` atual e o status desejado.
   - Valida a transição usando a lógica já criada no domínio (Prompt 02).
   - Gera um evento/registro de `legal_case_status_history` (apenas a estrutura de dados a ser persistida — a persistência real é responsabilidade de outro componente, via porta/interface).
2. Definir a porta (interface) `LegalCaseStatusHistoryRepository` no módulo `application`, a ser implementada depois em `infrastructure`.
3. Testes unitários cobrindo cada transição válida e inválida da seção 4, incluindo o caso `RETURNED_FOR_CORRECTION → RECEIVED`.

## Restrições
- Este serviço não deve conhecer detalhes de fila, banco ou API — apenas a regra de transição.
- Comentários/Javadoc em português.

## Critério de aceite
- Nenhum outro componente do sistema (controllers, consumers) pode alterar `LegalCaseStatus` diretamente sem passar por este serviço — deixe isso documentado no Javadoc da classe.
