-- Checklist documental determinístico (Prompt 09).

-- Tipo do documento informado pelo requisitante no upload (ex.: CONTRACT_DRAFT). É o que vincula o
-- documento a um item de checklist. Opcional: um documento sem tipo não satisfaz regra nenhuma.
ALTER TABLE documents ADD COLUMN document_type VARCHAR(100);

-- Um mesmo documento não pode ser exigido duas vezes pelo mesmo tipo de demanda.
CREATE UNIQUE INDEX uq_checklist_rules_case_type_document
    ON checklist_rules (case_type, required_document_type);

-- No máximo um item por regra em cada demanda: é o que torna a geração do checklist repetível.
CREATE UNIQUE INDEX uq_document_checklist_items_case_rule
    ON document_checklist_items (legal_case_id, checklist_rule_id);

CREATE INDEX idx_document_checklist_items_rule_id ON document_checklist_items (checklist_rule_id);

-- Mesma regra do domínio: item satisfeito aponta para o documento; os demais, não.
ALTER TABLE document_checklist_items
    ADD CONSTRAINT ck_document_checklist_items_status
        CHECK (status IN ('PENDING', 'SATISFIED', 'MISSING')),
    ADD CONSTRAINT ck_document_checklist_items_document
        CHECK ((status = 'SATISFIED') = (document_id IS NOT NULL));
