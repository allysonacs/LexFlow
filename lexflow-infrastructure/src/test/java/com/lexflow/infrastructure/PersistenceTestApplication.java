package com.lexflow.infrastructure;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Aplicação mínima usada apenas pelos testes deste módulo.
 *
 * <p>O módulo {@code lexflow-infrastructure} é uma biblioteca e não tem classe de inicialização
 * própria; os testes de fatia do Spring Boot, porém, precisam de uma para descobrir as entidades e
 * os repositórios.
 */
@SpringBootApplication
public class PersistenceTestApplication {}
