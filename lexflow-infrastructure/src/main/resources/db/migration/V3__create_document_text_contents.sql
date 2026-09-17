-- Texto extraído de cada documento (Prompt 08), nativo ou por OCR, sem nenhum uso de LLM.
-- É a entrada da extração estruturada de fatos (Prompt 11).
CREATE TABLE document_text_contents (
    id                UUID PRIMARY KEY,
    document_id       UUID         NOT NULL REFERENCES documents (id),
    legal_case_id     UUID         NOT NULL REFERENCES legal_cases (id),
    -- Nulo quando a extração falhou; vazio quando o arquivo foi lido e não tinha texto.
    content           TEXT,
    -- NATIVE_TEXT | OCR; nulo quando a extração falhou.
    extraction_method VARCHAR(20),
    -- EXTRACTED | NO_TEXT_FOUND | FAILED
    status            VARCHAR(20)  NOT NULL,
    failure_reason    VARCHAR(500),
    extracted_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_document_text_contents_status
        CHECK (status IN ('EXTRACTED', 'NO_TEXT_FOUND', 'FAILED')),
    CONSTRAINT ck_document_text_contents_method
        CHECK (extraction_method IS NULL OR extraction_method IN ('NATIVE_TEXT', 'OCR')),
    -- Mesma regra do domínio: falha tem motivo e não tem texto; sucesso tem texto e método.
    CONSTRAINT ck_document_text_contents_consistency CHECK (
        (status = 'FAILED' AND content IS NULL AND extraction_method IS NULL AND failure_reason IS NOT NULL)
        OR (status <> 'FAILED' AND content IS NOT NULL AND extraction_method IS NOT NULL AND failure_reason IS NULL)
    )
);

-- Um texto por documento: é o que torna a extração retomável sem duplicar registros.
CREATE UNIQUE INDEX uq_document_text_contents_document_id ON document_text_contents (document_id);
CREATE INDEX idx_document_text_contents_legal_case_id ON document_text_contents (legal_case_id);
