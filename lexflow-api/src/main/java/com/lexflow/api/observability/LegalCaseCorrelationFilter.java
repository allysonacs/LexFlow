package com.lexflow.api.observability;

import com.lexflow.infrastructure.observability.LegalCaseCorrelation;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Coloca o {@code legalCaseId} no log e no trace de toda requisição que fala sobre uma demanda
 * (Prompt 18, item 4).
 *
 * <p>O consumidor da fila já abre esse escopo para o processamento assíncrono. Sem este filtro,
 * porém, metade da história ficaria de fora: a ingestão, a consulta da análise e o registro da
 * decisão sairiam no log sem o identificador, e a pergunta "o que aconteceu com esta demanda?"
 * responderia apenas o que aconteceu <em>depois</em> da fila.
 *
 * <p>O identificador é lido do caminho, e não do corpo: ler o corpo aqui obrigaria a bufferizá-lo, e
 * o caminho já identifica a demanda em todas as rotas que tratam de uma.
 */
@Component
public class LegalCaseCorrelationFilter extends OncePerRequestFilter {

    /** Rotas de demanda: {@code /api/v1/legal-cases/{id}} e tudo o que vem depois. */
    private static final Pattern LEGAL_CASE_PATH = Pattern.compile(
            "/api/v1/legal-cases/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    private final ObjectProvider<Tracer> tracer;

    public LegalCaseCorrelationFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID legalCaseId = legalCaseIdOf(request.getRequestURI());
        if (legalCaseId == null) {
            // A criação de uma demanda ainda não tem identificador: o caso nasce dentro da requisição,
            // e a correlação dele começa no evento publicado ao final.
            chain.doFilter(request, response);
            return;
        }
        try (LegalCaseCorrelation correlation = LegalCaseCorrelation.open(tracer.getIfAvailable(), legalCaseId)) {
            chain.doFilter(request, response);
        }
    }

    /** Identificador da demanda no caminho, ou nulo quando a rota não trata de uma. */
    static UUID legalCaseIdOf(String path) {
        if (path == null) {
            return null;
        }
        Matcher matcher = LEGAL_CASE_PATH.matcher(path);
        return matcher.find() ? UUID.fromString(matcher.group(1)) : null;
    }
}
