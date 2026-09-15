# Prompt 05 — API REST de ingestão de demandas

Leia `docs/00-knowledge-base.md`, seções 1, 5 e 11 (idempotência).

## Objetivo
Criar o endpoint REST que recebe uma nova demanda jurídica com seus documentos.

## Escopo
1. `POST /api/v1/legal-cases` (multipart/form-data): recebe metadados da demanda (`caseType`, `requester`, `priority`, `externalReference`) e um ou mais arquivos.
2. Validação de entrada: `caseType` deve ser um dos valores válidos de `LegalCaseType`; arquivos com extensão/mime type permitidos (definir uma lista básica: pdf, docx, jpg, png).
3. Suporte a idempotência: o cliente pode enviar um header `Idempotency-Key`; se uma requisição com a mesma chave já foi processada, retornar a mesma resposta (201) sem duplicar o caso.
4. Ao receber, criar o `LegalCase` com status `RECEIVED` (via o serviço de transição do Prompt 04, ainda que seja o estado inicial) e persistir metadados dos documentos (o upload do binário em si é tratado no Prompt 06 — aqui pode-se assumir uma porta `DocumentStoragePort` ainda não implementada).
5. Resposta: `201 Created` com o `id` do `LegalCase` e a URL para consulta de status (`GET /api/v1/legal-cases/{id}`).
6. `GET /api/v1/legal-cases/{id}`: retorna status atual e metadados básicos.
7. Tratamento de erros padronizado (`@ControllerAdvice`) retornando um formato consistente de erro (código, mensagem, timestamp).

## Restrições
- Este endpoint não deve fazer nenhuma chamada síncrona de classificação ou IA — apenas recebe e enfileira (a publicação real do evento na fila é do Prompt 07; por ora, pode deixar um ponto de extensão claro, como uma porta `LegalCaseReceivedEventPublisher`).
- Comentários/Javadoc em português.

## Critério de aceite
- Testes de integração (`@SpringBootTest` + Testcontainers) cobrindo: criação bem-sucedida, reenvio com a mesma `Idempotency-Key` (não deve duplicar), `caseType` inválido (400).
