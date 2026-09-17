-- Métrica de negócio central: concordância entre a sugestão implícita da IA e a decisão humana
-- (Prompt 18, item 3).

-- É uma visão, e não uma tabela: o dado já existe em ai_analysis_responses, decisions e
-- legal_case_alerts. Duplicá-lo criaria uma terceira versão da verdade, que poderia divergir das
-- outras duas — e a métrica deixaria de medir o sistema para passar a medir a si mesma.
--
-- A "sugestão implícita" não é uma resposta da IA: é uma leitura do conjunto das respostas, definida
-- aqui e em um lugar só. FAVORABLE quando nada pede atenção; UNFAVORABLE quando alguma coisa pede.
CREATE VIEW ai_human_agreement AS
WITH answers AS (
    SELECT
        r.legal_case_id,
        count(*)                                                              AS answer_count,
        avg(r.confidence_score)                                               AS avg_confidence,
        min(r.confidence_score)                                               AS min_confidence,
        count(*) FILTER (WHERE r.verification_status = 'FAILED')              AS failed_verifications,
        count(*) FILTER (WHERE lower(r.answer_text) LIKE 'informação não encontrada na base normativa%')
                                                                              AS not_found_answers,
        count(*) FILTER (WHERE r.answer_source = 'DETERMINISTIC')             AS deterministic_answers
    FROM ai_analysis_responses r
    GROUP BY r.legal_case_id
),
open_alerts AS (
    SELECT a.legal_case_id, count(*) AS open_alerts
    FROM legal_case_alerts a
    WHERE a.resolved_at IS NULL
    GROUP BY a.legal_case_id
),
-- A decisão que vale é a última: uma demanda devolvida e decidida de novo tem duas linhas.
last_decision AS (
    SELECT DISTINCT ON (d.legal_case_id)
        d.legal_case_id, d.id AS decision_id, d.decision_type, d.decided_by, d.decided_at
    FROM decisions d
    ORDER BY d.legal_case_id, d.decided_at DESC, d.id DESC
)
SELECT
    c.id                                       AS legal_case_id,
    c.case_type,
    d.decision_id,
    d.decision_type,
    d.decided_at,
    coalesce(a.answer_count, 0)                AS answer_count,
    a.avg_confidence,
    a.min_confidence,
    coalesce(a.failed_verifications, 0)        AS failed_verifications,
    coalesce(a.not_found_answers, 0)           AS not_found_answers,
    coalesce(o.open_alerts, 0)                 AS open_alerts,
    CASE
        WHEN coalesce(a.answer_count, 0) = 0 THEN 'INCONCLUSIVE'
        WHEN coalesce(a.failed_verifications, 0) > 0 THEN 'UNFAVORABLE'
        WHEN coalesce(a.not_found_answers, 0) > 0 THEN 'UNFAVORABLE'
        WHEN coalesce(o.open_alerts, 0) > 0 THEN 'UNFAVORABLE'
        WHEN a.min_confidence < 0.7 THEN 'UNFAVORABLE'
        ELSE 'FAVORABLE'
    END                                        AS ai_suggestion,
    -- Concordância: a sugestão favorável corresponde à aprovação; a desfavorável, a qualquer outro
    -- desfecho. Sugestão inconclusiva não conta nem como acerto nem como erro.
    CASE
        WHEN coalesce(a.answer_count, 0) = 0 THEN NULL
        WHEN coalesce(a.failed_verifications, 0) > 0
             OR coalesce(a.not_found_answers, 0) > 0
             OR coalesce(o.open_alerts, 0) > 0
             OR a.min_confidence < 0.7
            THEN d.decision_type <> 'APPROVED'
        ELSE d.decision_type = 'APPROVED'
    END                                        AS agreed
FROM legal_cases c
JOIN last_decision d ON d.legal_case_id = c.id
LEFT JOIN answers a ON a.legal_case_id = c.id
LEFT JOIN open_alerts o ON o.legal_case_id = c.id;

COMMENT ON VIEW ai_human_agreement IS
    'Concordância entre a sugestão implícita da IA e a decisão humana (Prompt 18). Uma linha por demanda decidida.';
