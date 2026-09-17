-- Descrição livre da demanda, informada pelo requisitante na ingestão (Prompt 08).
-- É opcional e serve de sinal para a classificação determinística do tipo de demanda.
-- O limite acompanha LegalCase.DESCRIPTION_MAX_LENGTH.
ALTER TABLE legal_cases ADD COLUMN description VARCHAR(2000);
