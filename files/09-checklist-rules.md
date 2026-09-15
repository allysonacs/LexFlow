# Prompt 09 — Checklist documental determinístico

Leia `docs/00-knowledge-base.md`, seção 9, com atenção — esta é uma regra de negócio pura, sem IA.

## Objetivo
Implementar a verificação de documentação obrigatória por tipo de demanda.

## Escopo
1. CRUD administrativo simples (pode ser via endpoint REST protegido para papel `ADMIN`, ou via migration/seed inicial) para gerenciar `ChecklistRule` por `LegalCaseType`.
2. Seed inicial de regras razoáveis para cada tipo (ex.: `CONTRACT_SIGNING` exige minuta do contrato e parecer financeiro; `SUPPLIER_HIRING` exige CNPJ/documento de habilitação do fornecedor) — deixe explícito no Javadoc que isso é um ponto de configuração de negócio, não uma regra fixa no código.
3. Ao classificar uma demanda (após o Prompt 08), gerar automaticamente os `DocumentChecklistItem` correspondentes às `ChecklistRule` do seu `LegalCaseType`, todos iniciando como `MISSING`.
4. Vincular documentos recebidos aos itens de checklist correspondentes (por tipo de documento informado no upload), atualizando o status para `SATISFIED`.
5. Implementar a resposta determinística à pergunta `HAS_SUFFICIENT_DOCUMENTATION`: `true` somente se todos os itens `mandatory = true` estiverem `SATISFIED`.
6. Endpoint `GET /api/v1/legal-cases/{id}/checklist` retornando o status de cada item.
7. Testes cobrindo: geração correta dos itens por tipo, atualização de status ao vincular documento, e o cálculo de `HAS_SUFFICIENT_DOCUMENTATION` em cenários completos e incompletos.

## Restrições
- Zero uso de LLM aqui — isso é 100% regra de negócio determinística.
- Comentários/Javadoc em português.

## Critério de aceite
- Uma demanda sem todos os documentos obrigatórios nunca é marcada como tendo documentação suficiente, mesmo que o restante do pipeline tente avançar.
- Ponto de checagem: com este prompt concluído, o sistema já processa demandas de ponta a ponta (ingestão → classificação → checklist) sem nenhuma IA. Recomenda-se rodar um teste manual completo antes de seguir para a Fase 4.
