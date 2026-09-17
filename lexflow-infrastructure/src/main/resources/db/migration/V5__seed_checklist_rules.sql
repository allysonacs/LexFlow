-- Regras iniciais do checklist documental (Prompt 09).
--
-- ATENÇÃO: isto é um ponto de partida de CONFIGURAÇÃO DE NEGÓCIO, não uma regra fixa do sistema.
-- Quem decide quais documentos cada tipo de demanda exige é a área jurídica, e a manutenção é feita
-- pela API administrativa (/api/v1/admin/checklist-rules), sem novo deploy. Os identificadores são
-- fixos para que ambientes diferentes tenham as mesmas regras de partida.

INSERT INTO checklist_rules (id, case_type, required_document_type, description, mandatory) VALUES
    -- Podemos contratar esse fornecedor?
    ('9a0e7c1e-0001-4c10-8000-000000000001', 'SUPPLIER_HIRING', 'SUPPLIER_CNPJ_CARD',
        'Cartão CNPJ do fornecedor', TRUE),
    ('9a0e7c1e-0001-4c10-8000-000000000002', 'SUPPLIER_HIRING', 'SUPPLIER_QUALIFICATION_DOCUMENTS',
        'Documentos de habilitação do fornecedor: contrato social e certidões negativas', TRUE),
    ('9a0e7c1e-0001-4c10-8000-000000000003', 'SUPPLIER_HIRING', 'COMMERCIAL_PROPOSAL',
        'Proposta comercial do fornecedor', FALSE),

    -- Podemos assinar esse contrato?
    ('9a0e7c1e-0002-4c10-8000-000000000001', 'CONTRACT_SIGNING', 'CONTRACT_DRAFT',
        'Minuta do contrato a ser assinado', TRUE),
    ('9a0e7c1e-0002-4c10-8000-000000000002', 'CONTRACT_SIGNING', 'FINANCIAL_OPINION',
        'Parecer financeiro sobre o contrato', TRUE),
    ('9a0e7c1e-0002-4c10-8000-000000000003', 'CONTRACT_SIGNING', 'SIGNATORY_POWERS',
        'Documento que comprova os poderes dos signatários', FALSE),

    -- Podemos pagar esse acordo?
    ('9a0e7c1e-0003-4c10-8000-000000000001', 'SETTLEMENT_PAYMENT', 'SETTLEMENT_AGREEMENT',
        'Termo de acordo assinado pelas partes', TRUE),
    ('9a0e7c1e-0003-4c10-8000-000000000002', 'SETTLEMENT_PAYMENT', 'PAYMENT_INSTRUCTIONS',
        'Dados bancários ou guia para o pagamento', TRUE),
    ('9a0e7c1e-0003-4c10-8000-000000000003', 'SETTLEMENT_PAYMENT', 'COURT_APPROVAL',
        'Homologação judicial do acordo, quando houver processo', FALSE),

    -- Essa ação judicial pode ser encerrada?
    ('9a0e7c1e-0004-4c10-8000-000000000001', 'LAWSUIT_CLOSURE', 'CLOSURE_PETITION',
        'Petição de encerramento, desistência ou acordo nos autos', TRUE),
    ('9a0e7c1e-0004-4c10-8000-000000000002', 'LAWSUIT_CLOSURE', 'CASE_PROGRESS_REPORT',
        'Andamento processual atualizado', TRUE),
    ('9a0e7c1e-0004-4c10-8000-000000000003', 'LAWSUIT_CLOSURE', 'FINAL_JUDGMENT',
        'Sentença ou certidão de trânsito em julgado', FALSE),

    -- Podemos aceitar essa proposta?
    ('9a0e7c1e-0005-4c10-8000-000000000001', 'PROPOSAL_ACCEPTANCE', 'PROPOSAL_DOCUMENT',
        'Proposta recebida, com valores e condições', TRUE),
    ('9a0e7c1e-0005-4c10-8000-000000000002', 'PROPOSAL_ACCEPTANCE', 'FINANCIAL_OPINION',
        'Parecer financeiro sobre a proposta', TRUE),
    ('9a0e7c1e-0005-4c10-8000-000000000003', 'PROPOSAL_ACCEPTANCE', 'COUNTERPARTY_REGISTRATION',
        'Dados cadastrais da contraparte', FALSE);
