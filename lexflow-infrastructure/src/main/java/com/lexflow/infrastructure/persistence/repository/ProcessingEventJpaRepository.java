package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.infrastructure.persistence.entity.ProcessingEventEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Acesso à tabela {@code processing_events}, usada para garantir idempotência (seção 11). */
public interface ProcessingEventJpaRepository extends JpaRepository<ProcessingEventEntity, UUID> {

    Optional<ProcessingEventEntity> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * Apaga um lote de eventos já processados e mais antigos que o corte (Prompt 17, item 2).
     *
     * <p>Em lotes, e não de uma vez: a tabela é escrita por toda ingestão e por todo consumo, e um
     * {@code DELETE} sobre meses de histórico seguraria bloqueios longos justamente nela.
     *
     * <p>Só {@code PROCESSED}: um evento {@code FAILED} continua sendo evidência de um problema, e um
     * {@code IN_PROGRESS} pode estar em execução em outra réplica.
     *
     * @return quantas linhas foram apagadas; menos que o tamanho do lote significa que acabou
     */
    @Modifying
    @Query(
            value =
                    """
                    DELETE FROM processing_events
                    WHERE ctid IN (
                        SELECT ctid FROM processing_events
                        WHERE status = 'PROCESSED' AND processed_at < :cutoff
                        LIMIT :batchSize
                    )
                    """,
            nativeQuery = true)
    int deleteProcessedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
