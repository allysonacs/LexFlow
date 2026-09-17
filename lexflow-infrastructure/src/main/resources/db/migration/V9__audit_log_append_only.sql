-- Trilha de auditoria append-only (Prompt 16).

-- Correlação com a demanda. A trilha registra entidades diferentes — a demanda, uma resposta da IA,
-- uma decisão —, e sem esta coluna reconstruir a linha do tempo de um caso exigiria conhecer, de
-- fora, todos os identificadores que pertencem a ele.
ALTER TABLE audit_logs
    ADD COLUMN legal_case_id UUID REFERENCES legal_cases (id);

CREATE INDEX idx_audit_logs_legal_case_id ON audit_logs (legal_case_id, occurred_at);

-- A seção 12 exige que a trilha seja append-only. A garantia precisa estar no banco: uma regra que
-- só existe no código Java protege apenas o caminho que passa pelo código Java, e uma trilha que
-- pode ser corrigida depois não é trilha.
CREATE OR REPLACE FUNCTION lexflow_audit_logs_append_only() RETURNS TRIGGER AS $$
BEGIN
    -- Sem ERRCODE próprio: o padrão (P0001) é um erro de statement. Um código da classe 08, por
    -- exemplo, faria o pool tratar a conexão como perdida e descartá-la, o que é pior do que a
    -- própria recusa.
    RAISE EXCEPTION 'audit_logs e append-only: % nao e permitido', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION lexflow_audit_logs_append_only();

-- TRUNCATE não passa por gatilho de linha; por isso o gatilho de statement.
CREATE TRIGGER trg_audit_logs_no_truncate
    BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT
    EXECUTE FUNCTION lexflow_audit_logs_append_only();
