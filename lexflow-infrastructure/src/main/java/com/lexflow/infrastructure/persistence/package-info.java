/**
 * Camada de persistência do LexFlow: entidades JPA, mappers e repositórios Spring Data.
 *
 * <p>Nenhuma regra de negócio mora aqui. As entidades desta camada são espelhos das tabelas e vivem
 * separadas das entidades puras de {@code lexflow-domain}, que não conhecem JPA.
 */
package com.lexflow.infrastructure.persistence;
