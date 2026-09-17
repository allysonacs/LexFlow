package com.lexflow.application.legalcase;

import com.lexflow.application.pagination.PageQuery;
import com.lexflow.application.pagination.PageResult;
import com.lexflow.domain.legalcase.LegalCase;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída do agregado {@link LegalCase}.
 *
 * <p>Trafega o agregado de domínio, não a entidade JPA: é o adapter em {@code lexflow-infrastructure}
 * que conhece o banco.
 */
public interface LegalCaseRepository {

    /** Grava a demanda, criando ou atualizando a linha correspondente. */
    LegalCase save(LegalCase legalCase);

    Optional<LegalCase> findById(UUID id);

    /**
     * Listagem paginada, das demandas mais antigas para as mais recentes.
     *
     * <p>A ordem é a de chegada de propósito: a fila de revisão humana é atendida por ordem de
     * criação, e não pela última atualização, que mudaria de posição a cada evento do pipeline.
     *
     * @param status filtro opcional; nulo lista todos os status
     */
    PageResult<LegalCase> findAll(LegalCaseStatus status, PageQuery pageQuery);
}
