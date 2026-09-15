/**
 * Adapters de infraestrutura do LexFlow: persistência JPA/PostgreSQL, fila, storage S3/MinIO,
 * cliente LLM e RAG com pgvector.
 *
 * <p>Implementa as portas definidas em {@code lexflow-application}. Nenhuma regra de negócio deve
 * morar aqui.
 */
package com.lexflow.infrastructure;
