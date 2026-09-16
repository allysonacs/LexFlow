package com.lexflow.infrastructure.persistence.repository;

import com.lexflow.infrastructure.persistence.entity.DocumentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso à tabela {@code documents}. */
public interface DocumentJpaRepository extends JpaRepository<DocumentEntity, UUID> {

    List<DocumentEntity> findByLegalCaseId(UUID legalCaseId);

    /**
     * Documentos de uma demanda em ordem estável: data de envio, nome e identificador. Os arquivos de
     * uma mesma requisição compartilham a data de envio, e sem o desempate a ordem — que decide qual
     * documento satisfaz um item de checklist — variaria entre consultas.
     */
    List<DocumentEntity> findByLegalCaseIdOrderByUploadedAtAscFileNameAscIdAsc(UUID legalCaseId);

    /** Sustenta a detecção de reenvio do mesmo arquivo na mesma demanda (Prompt 06). */
    Optional<DocumentEntity> findByLegalCaseIdAndChecksumSha256(UUID legalCaseId, String checksumSha256);
}
