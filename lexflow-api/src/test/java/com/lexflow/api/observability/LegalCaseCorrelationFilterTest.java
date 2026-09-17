package com.lexflow.api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Tracer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Correlação das requisições HTTP com a demanda (Prompt 18, item 4).
 *
 * <p>Sem este filtro, o log da consulta da análise, do registro da decisão e da auditoria sairia sem
 * o identificador, e a linha do tempo de uma demanda começaria só depois da fila.
 */
class LegalCaseCorrelationFilterTest {

    private final LegalCaseCorrelationFilter filter = new LegalCaseCorrelationFilter(noTracer());

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("uma rota de demanda coloca o identificador no log durante a requisição")
    void shouldCorrelateLegalCaseRoutes() throws Exception {
        UUID legalCaseId = UUID.randomUUID();
        AtomicReference<String> duranteARequisicao = new AtomicReference<>();

        filter.doFilter(
                get("/api/v1/legal-cases/" + legalCaseId + "/analysis"),
                new MockHttpServletResponse(),
                chainThatReads(duranteARequisicao));

        assertThat(duranteARequisicao.get()).isEqualTo(legalCaseId.toString());
        // E não sobra nada para a próxima requisição atendida pela mesma thread.
        assertThat(MDC.get("legalCaseId")).isNull();
    }

    @Test
    @DisplayName("todas as rotas de uma demanda são correlacionadas, inclusive a decisão e a auditoria")
    void shouldCorrelateEveryLegalCaseRoute() {
        UUID legalCaseId = UUID.randomUUID();

        assertThat(LegalCaseCorrelationFilter.legalCaseIdOf("/api/v1/legal-cases/" + legalCaseId))
                .isEqualTo(legalCaseId);
        assertThat(LegalCaseCorrelationFilter.legalCaseIdOf("/api/v1/legal-cases/" + legalCaseId + "/decisions"))
                .isEqualTo(legalCaseId);
        assertThat(LegalCaseCorrelationFilter.legalCaseIdOf("/api/v1/legal-cases/" + legalCaseId + "/audit-log"))
                .isEqualTo(legalCaseId);
        assertThat(LegalCaseCorrelationFilter.legalCaseIdOf("/api/v1/legal-cases/" + legalCaseId + "/documents"))
                .isEqualTo(legalCaseId);
    }

    @Test
    @DisplayName("rotas sem demanda passam sem correlação, inclusive a criação")
    void shouldIgnoreRoutesWithoutLegalCase() throws Exception {
        AtomicReference<String> duranteARequisicao = new AtomicReference<>("não tocado");

        // A criação não tem identificador ainda: a demanda nasce dentro da requisição.
        filter.doFilter(get("/api/v1/legal-cases"), new MockHttpServletResponse(), chainThatReads(duranteARequisicao));

        assertThat(duranteARequisicao.get()).isNull();
        assertThat(LegalCaseCorrelationFilter.legalCaseIdOf("/api/v1/legal-cases?status=PENDING_HUMAN_REVIEW"))
                .isNull();
        assertThat(LegalCaseCorrelationFilter.legalCaseIdOf("/api/v1/legal-cases/nao-e-um-uuid"))
                .isNull();
        assertThat(LegalCaseCorrelationFilter.legalCaseIdOf("/actuator/health")).isNull();
        assertThat(LegalCaseCorrelationFilter.legalCaseIdOf(null)).isNull();
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }

    private static MockFilterChain chainThatReads(AtomicReference<String> holder) {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                holder.set(MDC.get("legalCaseId"));
            }
        };
    }

    /** Sem tracing configurado, a correlação continua valendo no log. */
    private static ObjectProvider<Tracer> noTracer() {
        return new ObjectProvider<>() {
            @Override
            public Tracer getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Tracer getIfAvailable() {
                return null;
            }

            @Override
            public Tracer getIfUnique() {
                return null;
            }

            @Override
            public Tracer getObject() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
