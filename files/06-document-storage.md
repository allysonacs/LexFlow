# Prompt 06 — Armazenamento de documentos (S3/MinIO)

Leia `docs/00-knowledge-base.md`, seções 7, 8 e 11.

## Objetivo
Implementar a porta `DocumentStoragePort` (definida no Prompt 05) com um adapter real para armazenamento de arquivos.

## Escopo
1. Adapter S3-compatível (funciona com AWS S3 em produção e MinIO em desenvolvimento), usando o AWS SDK v2.
2. Ao salvar um documento: calcular `checksum_sha256` do arquivo, gerar `storage_path` determinístico (ex.: `legal-cases/{legalCaseId}/{documentId}/{fileName}`), persistir metadados na tabela `documents`.
3. Detecção de duplicidade: se o mesmo `checksum_sha256` já existir para o mesmo `legal_case_id`, não duplicar o armazenamento (idempotência de upload).
4. Método de recuperação (download) do documento para uso futuro pelo pipeline de OCR/extração (Prompt 08).
5. Configuração via `application.yml` (bucket, região, endpoint customizável para apontar ao MinIO local).
6. Testes de integração usando um container MinIO via Testcontainers.

## Restrições
- Nunca logar o conteúdo do arquivo, apenas metadados (nome, tamanho, checksum).
- Comentários/Javadoc em português.

## Critério de aceite
- Upload e download de um arquivo de teste funcionam de ponta a ponta contra o MinIO do Testcontainers.
- Reenvio do mesmo arquivo (mesmo checksum) para o mesmo caso não gera um segundo objeto no storage.
