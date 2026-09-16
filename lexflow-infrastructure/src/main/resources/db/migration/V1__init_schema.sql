-- Schema inicial do LexFlow, transcrito da seção 6 da base de conhecimento.
-- Os nomes de tabela são plurais e os de coluna, singulares, em snake_case.

-- Extensão usada pela busca vetorial da base normativa (RAG, Prompt 12).
CREATE EXTENSION IF NOT EXISTS vector;

-- Demanda jurídica: agregado central do sistema.
CREATE TABLE legal_cases (
    id                 UUID PRIMARY KEY,
    external_reference VARCHAR(100),
    case_type          VARCHAR(50)  NOT NULL,
    status             VARCHAR(50)  NOT NULL,
    requester          VARCHAR(255) NOT NULL,
    priority           VARCHAR(20)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_legal_cases_status ON legal_cases (status);
CREATE INDEX idx_legal_cases_case_type ON legal_cases (case_type);

-- Histórico de transições de status: uma linha por transição (seção 4).
CREATE TABLE legal_case_status_history (
    id              UUID PRIMARY KEY,
    legal_case_id   UUID        NOT NULL REFERENCES legal_cases (id),
    previous_status VARCHAR(50),
    new_status      VARCHAR(50) NOT NULL,
    changed_at      TIMESTAMPTZ NOT NULL,
    changed_by      VARCHAR(255),
    reason          TEXT
);

CREATE INDEX idx_legal_case_status_history_legal_case_id ON legal_case_status_history (legal_case_id);

-- Metadados dos arquivos anexados. O binário fica no storage de objetos (Prompt 06).
CREATE TABLE documents (
    id              UUID PRIMARY KEY,
    legal_case_id   UUID         NOT NULL REFERENCES legal_cases (id),
    file_name       VARCHAR(255) NOT NULL,
    storage_path    VARCHAR(1024) NOT NULL,
    mime_type       VARCHAR(255) NOT NULL,
    checksum_sha256 VARCHAR(64)  NOT NULL,
    uploaded_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_documents_legal_case_id ON documents (legal_case_id);
-- Sustenta a detecção de reenvio do mesmo arquivo na mesma demanda (Prompt 06).
CREATE UNIQUE INDEX uq_documents_case_checksum ON documents (legal_case_id, checksum_sha256);

-- Configuração de negócio: quais documentos cada tipo de demanda exige (seção 9).
CREATE TABLE checklist_rules (
    id                     UUID PRIMARY KEY,
    case_type              VARCHAR(50)  NOT NULL,
    required_document_type VARCHAR(100) NOT NULL,
    description            TEXT,
    mandatory              BOOLEAN      NOT NULL
);

CREATE INDEX idx_checklist_rules_case_type ON checklist_rules (case_type);

-- Aplicação de uma regra de checklist a uma demanda específica.
CREATE TABLE document_checklist_items (
    id                UUID PRIMARY KEY,
    legal_case_id     UUID        NOT NULL REFERENCES legal_cases (id),
    checklist_rule_id UUID        NOT NULL REFERENCES checklist_rules (id),
    status            VARCHAR(20) NOT NULL,
    document_id       UUID REFERENCES documents (id),
    evaluated_at      TIMESTAMPTZ
);

CREATE INDEX idx_document_checklist_items_legal_case_id ON document_checklist_items (legal_case_id);

-- Fatos extraídos de um documento pela IA, sem opinião jurídica (seção 10, item 1).
CREATE TABLE ai_extracted_facts (
    id             UUID PRIMARY KEY,
    legal_case_id  UUID         NOT NULL REFERENCES legal_cases (id),
    document_id    UUID         NOT NULL REFERENCES documents (id),
    extracted_json JSONB        NOT NULL,
    model_version  VARCHAR(100) NOT NULL,
    extracted_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_ai_extracted_facts_legal_case_id ON ai_extracted_facts (legal_case_id);

-- Fonte normativa indexada para RAG: legislação ou política interna.
CREATE TABLE knowledge_base_sources (
    id             UUID PRIMARY KEY,
    title          VARCHAR(500) NOT NULL,
    source_type    VARCHAR(50)  NOT NULL,
    effective_date DATE
);

-- Trecho de uma fonte normativa, com o embedding usado na recuperação.
-- A dimensão 1536 acompanha o modelo de embeddings escolhido; trocar de modelo exige nova migration.
CREATE TABLE knowledge_base_chunks (
    id          UUID PRIMARY KEY,
    source_id   UUID    NOT NULL REFERENCES knowledge_base_sources (id),
    chunk_index INTEGER NOT NULL,
    content     TEXT    NOT NULL,
    embedding   VECTOR(1536)
);

CREATE UNIQUE INDEX uq_knowledge_base_chunks_source_index ON knowledge_base_chunks (source_id, chunk_index);

-- Índice vetorial para busca por similaridade de cosseno (seção 10, item 2).
-- HNSW funciona em tabela vazia, ao contrário do ivfflat, que exige dados para treinar as listas.
CREATE INDEX idx_knowledge_base_chunks_embedding
    ON knowledge_base_chunks USING hnsw (embedding vector_cosine_ops);

-- Template de prompt versionado, para rastrear qual versão gerou cada resposta.
CREATE TABLE prompt_versions (
    id            UUID PRIMARY KEY,
    prompt_key    VARCHAR(100) NOT NULL,
    version       INTEGER      NOT NULL,
    template_text TEXT         NOT NULL,
    active        BOOLEAN      NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX uq_prompt_versions_key_version ON prompt_versions (prompt_key, version);

-- Resposta da IA a uma pergunta jurídica, com fonte citada e confiança (seção 10).
CREATE TABLE ai_analysis_responses (
    id                UUID PRIMARY KEY,
    legal_case_id     UUID             NOT NULL REFERENCES legal_cases (id),
    question_key      VARCHAR(50)      NOT NULL,
    answer_text       TEXT             NOT NULL,
    confidence_score  DOUBLE PRECISION NOT NULL,
    cited_chunks      JSONB            NOT NULL,
    model_version     VARCHAR(100)     NOT NULL,
    prompt_version_id UUID REFERENCES prompt_versions (id),
    created_at        TIMESTAMPTZ      NOT NULL
);

CREATE INDEX idx_ai_analysis_responses_legal_case_id ON ai_analysis_responses (legal_case_id);

-- Decisão final do responsável humano.
CREATE TABLE decisions (
    id            UUID PRIMARY KEY,
    legal_case_id UUID         NOT NULL REFERENCES legal_cases (id),
    decision_type VARCHAR(30)  NOT NULL,
    decided_by    VARCHAR(255) NOT NULL,
    decided_at    TIMESTAMPTZ  NOT NULL,
    comments      TEXT
);

CREATE INDEX idx_decisions_legal_case_id ON decisions (legal_case_id);

-- Trilha de auditoria append-only (seção 12). A restrição de UPDATE/DELETE entra no Prompt 16.
CREATE TABLE audit_logs (
    id          UUID PRIMARY KEY,
    entity_type VARCHAR(100) NOT NULL,
    entity_id   UUID         NOT NULL,
    action      VARCHAR(100) NOT NULL,
    actor       VARCHAR(255) NOT NULL,
    payload     JSONB,
    occurred_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_audit_logs_entity ON audit_logs (entity_type, entity_id);

-- Controle de idempotência do processamento assíncrono (seção 11).
CREATE TABLE processing_events (
    id              UUID PRIMARY KEY,
    event_type      VARCHAR(100) NOT NULL,
    aggregate_id    UUID         NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    payload         JSONB,
    created_at      TIMESTAMPTZ  NOT NULL,
    processed_at    TIMESTAMPTZ
);

-- Chave única: é o que garante que uma mensagem repetida não seja processada duas vezes.
CREATE UNIQUE INDEX uq_processing_events_idempotency_key ON processing_events (idempotency_key);
