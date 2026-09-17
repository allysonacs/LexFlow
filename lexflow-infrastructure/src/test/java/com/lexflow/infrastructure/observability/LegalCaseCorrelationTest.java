package com.lexflow.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Correlação por demanda (Prompt 18, itens 1 e 4).
 *
 * <p>O que estes testes fixam é a condição para responder "o que aconteceu com esta demanda?": todo
 * log do trecho sai com o identificador, o trace carrega o mesmo identificador, e nada disso vaza
 * para o próximo trabalho executado na mesma thread.
 */
class LegalCaseCorrelationTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("dentro do escopo, o identificador está no log, no trace e no span")
    void shouldCorrelateInsideTheScope() {
        SimpleTracer tracer = new SimpleTracer();
        Span span = tracer.nextSpan().name("processamento").start();
        UUID legalCaseId = UUID.randomUUID();

        try (Tracer.SpanInScope ignored = tracer.withSpan(span);
                LegalCaseCorrelation correlation = LegalCaseCorrelation.open(tracer, legalCaseId)) {
            assertThat(MDC.get(LegalCaseCorrelation.FIELD)).isEqualTo(legalCaseId.toString());
            assertThat(tracer.currentSpan().getTags()).containsEntry("legal_case_id", legalCaseId.toString());
            assertThat(tracer.getBaggage(LegalCaseCorrelation.FIELD).get()).isEqualTo(legalCaseId.toString());
        }
    }

    @Test
    @DisplayName("fora do escopo, nada sobra: a próxima demanda não herda o identificador da anterior")
    void shouldNotLeakAfterTheScope() {
        SimpleTracer tracer = new SimpleTracer();
        Span span = tracer.nextSpan().name("processamento").start();

        try (Tracer.SpanInScope ignored = tracer.withSpan(span);
                LegalCaseCorrelation correlation = LegalCaseCorrelation.open(tracer, UUID.randomUUID())) {
            assertThat(MDC.get(LegalCaseCorrelation.FIELD)).isNotNull();
        }

        // Um MDC que vaza faz a próxima demanda aparecer com o identificador da anterior — um erro
        // pior do que não ter correlação, porque parece informação boa.
        assertThat(MDC.get(LegalCaseCorrelation.FIELD)).isNull();
    }

    @Test
    @DisplayName("sem tracing configurado, a correlação continua valendo no log")
    void shouldCorrelateWithoutTracer() {
        UUID legalCaseId = UUID.randomUUID();

        try (LegalCaseCorrelation correlation = LegalCaseCorrelation.open(null, legalCaseId)) {
            assertThat(MDC.get(LegalCaseCorrelation.FIELD)).isEqualTo(legalCaseId.toString());
        }
        assertThat(MDC.get(LegalCaseCorrelation.FIELD)).isNull();
    }

    @Test
    @DisplayName("um escopo aninhado não apaga o identificador de quem o abriu")
    void shouldKeepTheOuterScopeUntouched() {
        UUID outer = UUID.randomUUID();

        try (LegalCaseCorrelation externo = LegalCaseCorrelation.open(null, outer)) {
            try (LegalCaseCorrelation interno = LegalCaseCorrelation.open(null, UUID.randomUUID())) {
                // Quem já tinha o campo no MDC continua sendo o dono dele.
                assertThat(MDC.get(LegalCaseCorrelation.FIELD)).isEqualTo(outer.toString());
            }
            assertThat(MDC.get(LegalCaseCorrelation.FIELD)).isEqualTo(outer.toString());
        }
        assertThat(MDC.get(LegalCaseCorrelation.FIELD)).isNull();
    }

    @Test
    @DisplayName("sem identificador, o escopo não faz nada")
    void shouldDoNothingWithoutLegalCaseId() {
        try (LegalCaseCorrelation correlation = LegalCaseCorrelation.open(null, null)) {
            assertThat(MDC.get(LegalCaseCorrelation.FIELD)).isNull();
        }
    }
}
