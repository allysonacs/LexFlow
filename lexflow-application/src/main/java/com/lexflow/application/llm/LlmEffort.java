package com.lexflow.application.llm;

import java.util.Locale;

/**
 * Esforço de raciocínio pedido ao modelo: troca profundidade por custo e latência.
 *
 * <p>Nos modelos atuais é o principal ajuste de qualidade — no lugar da temperatura, que eles não
 * aceitam mais.
 */
public enum LlmEffort {
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX;

    /** Valor no formato da API ({@code low}, {@code medium}...). */
    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
