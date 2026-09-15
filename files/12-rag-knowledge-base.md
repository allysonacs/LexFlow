# Prompt 12 — Base normativa e RAG (pgvector)

Leia `docs/00-knowledge-base.md`, seções 6, 10 e 8 (stack).

## Objetivo
Construir a infraestrutura de recuperação de informação (RAG) sobre legislação e políticas internas, usada para fundamentar as respostas do LLM.

## Escopo
1. Endpoint/ferramenta administrativa para ingestão de fontes normativas: `POST /api/v1/knowledge-base/sources`, aceitando texto ou arquivo, criando um `KnowledgeBaseSource`.
2. Pipeline de chunking: dividir o texto da fonte em pedaços coerentes (por parágrafo/seção, com sobreposição configurável), gerando `KnowledgeBaseChunk`.
3. Geração de embeddings para cada chunk (via a mesma API de LLM ou um endpoint de embeddings dedicado do provedor), persistidos na coluna `embedding` (`pgvector`).
4. `KnowledgeBaseRetriever`: dado um texto de consulta (a pergunta jurídica + contexto da demanda), retornar os N chunks mais similares (busca por distância de cosseno) usando o índice vetorial criado no Prompt 03.
5. Testes de integração com Testcontainers Postgres+pgvector, validando que a busca retorna os chunks mais relevantes para uma consulta de teste conhecida.

## Restrições
- Este componente não decide nada juridicamente — apenas recupera texto relevante.
- Comentários/Javadoc em português.

## Critério de aceite
- Ingerir 2-3 documentos normativos de teste e confirmar que uma consulta relacionada a um deles retorna os chunks corretos entre os top-3 resultados.
