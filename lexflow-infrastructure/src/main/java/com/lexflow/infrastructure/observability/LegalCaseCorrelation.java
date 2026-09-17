package com.lexflow.infrastructure.observability;

import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Amarra tudo o que acontece em uma demanda ao seu identificador (Prompt 18, itens 1 e 4).
 *
 * <p>São três amarrações ao mesmo tempo, porque cada uma serve a uma pergunta diferente:
 *
 * <ul>
 *   <li><strong>MDC</strong>: todo log daquele trecho de execução sai com {@code legalCaseId}. É o
 *       que permite responder "o que aconteceu com esta demanda?" partindo do identificador;
 *   <li><strong>baggage</strong>: o identificador viaja com o trace, inclusive através da fila e para
 *       outra réplica. É o que mantém a correlação depois que a requisição HTTP já terminou;
 *   <li><strong>atributo do span</strong>: o trace fica pesquisável por demanda na ferramenta de
 *       tracing, sem depender de casar log com trace na mão.
 * </ul>
 *
 * <p>O escopo é fechado no fim do bloco, sempre: um MDC que vaza faz a próxima demanda processada
 * naquela thread aparecer com o identificador da anterior — um erro pior do que não ter correlação
 * nenhuma, porque parece informação boa.
 */
public final class LegalCaseCorrelation implements AutoCloseable {

    /** Nome usado no MDC, no baggage e no atributo do span. */
    public static final String FIELD = "legalCaseId";

    private final BaggageInScope baggage;
    private final boolean mdcOwner;

    private LegalCaseCorrelation(BaggageInScope baggage, boolean mdcOwner) {
        this.baggage = baggage;
        this.mdcOwner = mdcOwner;
    }

    /**
     * Abre o escopo de correlação de uma demanda.
     *
     * @param tracer pode ser nulo: sem tracing configurado, a correlação continua valendo no log
     */
    public static LegalCaseCorrelation open(Tracer tracer, UUID legalCaseId) {
        if (legalCaseId == null) {
            return new LegalCaseCorrelation(null, false);
        }
        String value = legalCaseId.toString();
        boolean mdcOwner = MDC.get(FIELD) == null;
        if (mdcOwner) {
            MDC.put(FIELD, value);
        }

        BaggageInScope baggage = null;
        if (tracer != null) {
            Span span = tracer.currentSpan();
            if (span != null) {
                span.tag("legal_case_id", value);
            }
            // O baggage precisa de um contexto de trace para viajar. Sem trace ativo — amostragem
            // desligada, por exemplo —, a correlação continua valendo no log, que é o essencial.
            TraceContext context = span == null ? null : span.context();
            if (context != null) {
                baggage = tracer.createBaggageInScope(context, FIELD, value);
            }
        }
        return new LegalCaseCorrelation(baggage, mdcOwner);
    }

    @Override
    public void close() {
        if (baggage != null) {
            baggage.close();
        }
        if (mdcOwner) {
            MDC.remove(FIELD);
        }
    }
}
