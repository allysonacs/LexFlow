# Prompt 14 — Segunda checagem (self-verification)

Leia `docs/00-knowledge-base.md`, seção 10, item 4.

## Objetivo
Adicionar uma camada extra de verificação para as perguntas jurídicas mais críticas, reduzindo o risco de alucinação residual.

## Escopo
1. Definir quais `question_key` são consideradas críticas (ver seção 10 da base de conhecimento: `CAN_SIGN_CONTRACT`, `CAN_PAY_SETTLEMENT`, `CAN_CLOSE_LAWSUIT` — outras podem ser adicionadas via configuração).
2. Para essas perguntas, após obter a resposta do Prompt 13, disparar uma segunda chamada ao LLM (`VerifyAiAnalysisResponseUseCase`) com um prompt de verificação: fornecer a resposta gerada, os `cited_chunks` e pedir ao modelo para confirmar, com `true`/`false` e justificativa, se a resposta é de fato suportada pelo texto citado.
3. Se a verificação falhar (`false`), marcar a `AiAnalysisResponse` com um campo `verificationStatus = FAILED` (adicionar coluna via migration) e reduzir/zerar a `confidence_score`, sinalizando claramente ao humano que aquela resposta específica precisa de atenção redobrada.
4. Se passar, marcar `verificationStatus = VERIFIED`.
5. Testes cobrindo os dois cenários (verificação aprovada e reprovada) com stub do LLM.

## Restrições
- Esta segunda chamada não deve reescrever a resposta original — apenas validar e sinalizar.
- Comentários/Javadoc em português.

## Critério de aceite
- Uma resposta deliberadamente inconsistente com os trechos citados (fixture de teste) é corretamente marcada como `FAILED` e tem sua confiança reduzida.
