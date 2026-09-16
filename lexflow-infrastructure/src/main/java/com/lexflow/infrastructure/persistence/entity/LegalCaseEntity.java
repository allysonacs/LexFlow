package com.lexflow.infrastructure.persistence.entity;

import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapeamento da tabela {@code legal_cases}.
 *
 * <p>Espelho da tabela, sem nenhuma regra de negócio: a conversão para o agregado de domínio é
 * responsabilidade de {@link com.lexflow.infrastructure.persistence.mapper.LegalCaseMapper}.
 */
@Entity
@Table(name = "legal_cases")
public class LegalCaseEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "external_reference", length = 100)
    private String externalReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_type", nullable = false, length = 50)
    private LegalCaseType caseType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private LegalCaseStatus status;

    @Column(name = "requester", nullable = false)
    private String requester;

    @Column(name = "description", length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    private CasePriority priority;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LegalCaseEntity() {
        // exigido pelo JPA
    }

    public LegalCaseEntity(
            UUID id,
            String externalReference,
            LegalCaseType caseType,
            LegalCaseStatus status,
            String requester,
            String description,
            CasePriority priority,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.externalReference = externalReference;
        this.caseType = caseType;
        this.status = status;
        this.requester = requester;
        this.description = description;
        this.priority = priority;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public LegalCaseType getCaseType() {
        return caseType;
    }

    public LegalCaseStatus getStatus() {
        return status;
    }

    public String getRequester() {
        return requester;
    }

    public String getDescription() {
        return description;
    }

    public CasePriority getPriority() {
        return priority;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
