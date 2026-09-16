package com.lexflow.infrastructure.persistence.entity;

import com.lexflow.domain.legalcase.LegalCaseType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Mapeamento da tabela {@code checklist_rules}, que guarda a configuração de negócio do checklist. */
@Entity
@Table(name = "checklist_rules")
public class ChecklistRuleEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_type", nullable = false, length = 50)
    private LegalCaseType caseType;

    @Column(name = "required_document_type", nullable = false, length = 100)
    private String requiredDocumentType;

    @Column(name = "description")
    private String description;

    @Column(name = "mandatory", nullable = false)
    private boolean mandatory;

    protected ChecklistRuleEntity() {
        // exigido pelo JPA
    }

    public ChecklistRuleEntity(
            UUID id, LegalCaseType caseType, String requiredDocumentType, String description, boolean mandatory) {
        this.id = id;
        this.caseType = caseType;
        this.requiredDocumentType = requiredDocumentType;
        this.description = description;
        this.mandatory = mandatory;
    }

    public UUID getId() {
        return id;
    }

    public LegalCaseType getCaseType() {
        return caseType;
    }

    public String getRequiredDocumentType() {
        return requiredDocumentType;
    }

    public String getDescription() {
        return description;
    }

    public boolean isMandatory() {
        return mandatory;
    }
}
