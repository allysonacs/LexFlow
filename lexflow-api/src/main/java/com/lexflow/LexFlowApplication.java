package com.lexflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ponto de inicialização da aplicação LexFlow.
 *
 * <p>Fica no pacote raiz {@code com.lexflow} para que o component scan alcance os beans de todos os
 * módulos ({@code api}, {@code infrastructure} e {@code application}).
 */
@SpringBootApplication
public class LexFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(LexFlowApplication.class, args);
    }
}
