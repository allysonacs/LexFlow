# Prompt 03 — Camada de persistência (JPA + Flyway)

Leia `docs/00-knowledge-base.md`, seção 6 (modelo de dados), antes de começar.

## Objetivo
Mapear o domínio para persistência em PostgreSQL no módulo `lexflow-infrastructure`, com migrations versionadas via Flyway.

## Escopo
1. Migrations Flyway (`V1__init_schema.sql`, etc.) criando exatamente as tabelas listadas na seção 6 da base de conhecimento, com os nomes de coluna especificados. Incluir a extensão `pgvector` na migration inicial (`CREATE EXTENSION IF NOT EXISTS vector;`).
2. Entidades JPA (`@Entity`) mapeando as tabelas — mantenha estas classes separadas das entidades de domínio puras do Prompt 02 (não misture anotações JPA no módulo `domain`). Use um padrão de mapper (`LegalCaseMapper`, etc.) para converter entre domínio e entidade JPA.
3. `Repository` (Spring Data JPA) para cada agregado.
4. Índices necessários: `legal_cases(status)`, `legal_cases(case_type)`, `document_checklist_items(legal_case_id)`, `processing_events(idempotency_key)` (único), índice vetorial em `knowledge_base_chunks(embedding)` usando `ivfflat` ou `hnsw` conforme suportado.
5. Testes de integração com Testcontainers (Postgres real) cobrindo: persistência e recuperação de um `LegalCase` completo, e a constraint de unicidade de `idempotency_key`.

## Restrições
- Colunas `jsonb` mapeadas com o tipo apropriado (ex.: `@JdbcTypeCode(SqlTypes.JSON)` no Hibernate 6).
- Nenhuma lógica de negócio nesta camada — só mapeamento e acesso a dados.
- Comentários/Javadoc em português.

## Critério de aceite
- `./gradlew :lexflow-infrastructure:test` roda as migrations num Postgres via Testcontainers e passa.
- Um `LegalCase` salvo e recuperado preserva todos os dados corretamente, incluindo colunas jsonb.
