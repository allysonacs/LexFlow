# Prompt 02 — Modelo de domínio (sem framework)

Leia `docs/00-knowledge-base.md` antes de começar, especialmente as seções 3 (glossário), 4 (máquina de estados) e 5 (tipos de demanda).

## Objetivo
Implementar as entidades e value objects do domínio no módulo `lexflow-domain`, **sem qualquer dependência de framework** (nada de `@Entity`, `@Component`, etc. — isso vem no Prompt 03).

## Escopo
1. Enums: `LegalCaseType`, `LegalCaseStatus`, `DecisionType`, `ChecklistItemStatus` — exatamente com os valores definidos na base de conhecimento.
2. Entidades/agregados:
   - `LegalCase` (agregado raiz): id, tipo, status, requester, priority, timestamps. Deve expor métodos de transição de status que aplicam as regras da máquina de estados (seção 4), lançando `InvalidStatusTransitionException` para transições inválidas.
   - `Document`: metadados do documento (sem o binário).
   - `ChecklistRule` e `DocumentChecklistItem`.
   - `AiExtractedFact`.
   - `AiAnalysisResponse` (com `questionKey`, `answer`, `confidenceScore`, `citedChunks`, `modelVersion`).
   - `Decision`.
3. Value objects onde fizer sentido (ex.: `ConfidenceScore` validando range 0.0–1.0).
4. Exceções de domínio: `DomainException` (base) e as específicas citadas na base de conhecimento.
5. Interface `LegalCaseStatusTransition` ou similar, isolando a lógica da máquina de estados para ser testável separadamente.
6. Testes unitários (JUnit 5) cobrindo:
   - Todas as transições válidas e inválidas de `LegalCaseStatus`.
   - Validações dos value objects.

## Restrições
- Zero dependência de Spring/JPA neste módulo.
- Entidades imutáveis onde fizer sentido (preferir retornar novo estado a usar setters).
- Comentários/Javadoc em português; identificadores em inglês.

## Critério de aceite
- Cobertura de teste do módulo `lexflow-domain` ≥ 80%.
- Nenhuma transição de status inválida passa despercebida (deve haver teste explícito para cada transição proibida citada na base de conhecimento).
