# LexFlow

O LexFlow automatiza a primeira camada de análise de demandas jurídicas: recebe a documentação, classifica o tipo de demanda, confere o checklist documental e usa IA com uma base normativa (RAG) para apoiar a decisão de um responsável humano.

O princípio que guia o sistema: **a IA nunca decide sozinha**. Ela responde citando a fonte e o nível de confiança; a decisão é sempre de uma pessoa. Tudo que é regra determinística, como saber se um documento obrigatório está presente, é resolvido por código, nunca por LLM.

> A fonte de verdade sobre domínio, regras de negócio, convenções e arquitetura é o arquivo [`docs/00-knowledge-base.md`](docs/00-knowledge-base.md). Leia esse arquivo antes de contribuir.

## Stack

- Java 21 (com virtual threads habilitadas)
- Spring Boot 3.5
- Gradle 9 (Kotlin DSL), multi-módulo, com o wrapper versionado
- PostgreSQL 17 + pgvector, com migrations em Flyway
- MinIO (compatível com S3) para os documentos, via AWS SDK v2
- RabbitMQ para o processamento assíncrono, via Spring AMQP
- Apache Tika 3.3 para extrair texto, com o Tesseract 5 para o OCR
- Spring Security (HTTP Basic) para a API administrativa
- Claude (Messages API da Anthropic) como LLM, chamado por `WebClient`, com Resilience4j (retry, circuit breaker, time limiter e bulkhead)
- Voyage AI como provedor de embeddings da base normativa, no mesmo desenho de cliente e proteções
- Testcontainers para os testes de integração
- JaCoCo para a verificação de cobertura

## Andamento

Os prompts de implementação ficam em `files/` e são executados em ordem.

| Prompt | Tema | Situação |
|---|---|---|
| 01 | Esqueleto do projeto (Gradle multi-módulo, Spring Boot, perfis) | concluído |
| 02 | Modelo de domínio | concluído |
| 03 | Camada de persistência (Flyway, JPA, pgvector) | concluído |
| 04 | Máquina de estados e serviço de transição | concluído |
| 05 | API REST de ingestão, com idempotência | concluído |
| 06 | Storage de documentos (S3/MinIO) | concluído |
| 07 | Fila e orquestração assíncrona (RabbitMQ, retry, dead-letter) | concluído |
| 08 | Classificação determinística e extração de texto (Tika + Tesseract) | concluído |
| 09 | Checklist documental determinístico, com API administrativa das regras | concluído |
| 10 | Cliente LLM isolado e resiliente (Messages API da Anthropic) | concluído |
| 11 | Extração estruturada de fatos via LLM, com prompt versionado e alertas | concluído |
| 12 | Base normativa e RAG (pgvector) | concluído |
| 13 | Orquestração do prompt chain (respostas às perguntas jurídicas) | concluído |
| 14 | Segunda checagem (self-verification) | concluído |
| 15 | API de revisão humana e decisão | próximo |
| 16–19 | Auditoria, resiliência, observabilidade e dataset de regressão | pendentes |

Hoje o pipeline vai da ingestão até as respostas da IA, prontas para o revisor humano:

```
POST /api/v1/legal-cases ──► RECEIVED ──(fila)──► CLASSIFYING ──► EXTRACTING ──► AI_ANALYSIS_IN_PROGRESS ──► PENDING_HUMAN_REVIEW
  grava demanda,                          classificação      checklist gerado    fatos extraídos pelo LLM   uma resposta por
  documentos (com tipo)                   por palavras-chave e avaliado; texto   (ai_extracted_facts)       pergunta jurídica,
  e histórico                                                de cada documento                              com a fonte citada
                                                             (Tika/Tesseract)                               (ai_analysis_responses)
                                                                       │                          │
                                                                       └─ alerta no documento     └─ alerta na análise:
                                                                          (fica em EXTRACTING)       resposta inválida ou recusa

GET /api/v1/legal-cases/{id}/checklist ──► HAS_SUFFICIENT_DOCUMENTATION, por regra determinística

POST /api/v1/knowledge-base/sources ──► norma dividida em trechos ──► embeddings ──► pgvector
                                        (é daqui que saem os trechos citados nas respostas)
```

As decisões tomadas até aqui estão consolidadas na seção 14 da base de conhecimento.

## Estrutura de módulos

A arquitetura é hexagonal (ports & adapters). As dependências entre os módulos apontam sempre para dentro, em direção ao domínio:

```
lexflow-api ──► lexflow-infrastructure ──► lexflow-application ──► lexflow-domain
```

| Módulo | Responsabilidade | Pode depender de |
|---|---|---|
| `lexflow-domain` | Entidades, value objects, regras puras e máquina de estados | Nenhum framework |
| `lexflow-application` | Casos de uso e portas (interfaces) | `lexflow-domain` |
| `lexflow-infrastructure` | Adapters: JPA/PostgreSQL, fila, storage S3/MinIO, cliente LLM e RAG | `application`, `domain` e bibliotecas de integração |
| `lexflow-api` | Controllers REST, DTOs, tratamento de erros e a classe `@SpringBootApplication` | Todos os módulos acima |

O pacote raiz é `com.lexflow`. A classe `LexFlowApplication` fica nesse pacote para que o component scan encontre os beans de todos os módulos.

## O que já está implementado

### Domínio (`lexflow-domain`)

Java puro, sem uma única dependência. As entidades são `record` imutáveis: uma transição de status devolve uma nova instância, em vez de alterar a existente.

| Pacote | Conteúdo |
|---|---|
| `legalcase` | `LegalCase` (agregado raiz), `LegalCaseStatus`, `LegalCaseType`, `CasePriority`, `LegalCaseStatusTransition` e `LegalCaseStatusTransitionRules` |
| `document` | `Document`, `Sha256Checksum`, `DocumentFormat`, `DocumentTypeCode`, `DocumentTextContent`, `TextExtractionMethod` e `TextExtractionStatus` |
| `alert` | `LegalCaseAlert` e `LegalCaseAlertType`: situações que exigem atenção humana e seguram o pipeline |
| `classification` | `LegalCaseKeywordClassifier`, `LegalCaseClassification`, `KeywordClassification` e `ClassificationOutcome` |
| `checklist` | `ChecklistRule`, `DocumentChecklistItem`, `ChecklistItemStatus` e `DocumentChecklist` (gera, avalia e responde `HAS_SUFFICIENT_DOCUMENTATION`) |
| `ai` | `AiExtractedFact` (com a versão do prompt usada), `AiAnalysisResponse`, `ConfidenceScore`, `QuestionKey`, `VerificationStatus`, `PromptVersion` |
| `decision` | `Decision`, `DecisionType` |
| `exception` | `DomainException` (base) e as exceções específicas de cada regra |

Duas regras que o domínio faz cumprir sozinho, sem depender de nenhuma camada externa:

- **Máquina de estados.** As transições permitidas ficam isoladas em `LegalCaseStatusTransitionRules`, transcritas da seção 4 da base de conhecimento. Qualquer outra transição lança `InvalidStatusTransitionException`. O teste cobre a matriz completa dos 81 pares de status, tanto nas regras do domínio quanto no serviço de aplicação que as usa.
- **Resposta da IA sem fonte é recusada.** O construtor de `AiAnalysisResponse` rejeita uma resposta sem `citedChunks`, a não ser que o texto declare que a informação não está na base normativa. Uma alucinação sem fonte não consegue nem ser instanciada.

### Aplicação (`lexflow-application`)

Casos de uso e portas. Também sem framework: depende apenas do domínio.

- **`LegalCaseStatusTransitionService`** é o **ponto único de mudança de status** do sistema. Nenhum outro componente — controller, consumer de fila, job ou repositório — pode alterar o `LegalCaseStatus` diretamente. O serviço valida a transição pelas regras do domínio e devolve, na mesma operação, a demanda já no novo status e o `LegalCaseStatusHistoryEntry` correspondente. Os dois andam juntos justamente para que nenhuma demanda mude de status sem deixar rastro no histórico.
- O serviço é puro: não conhece banco, fila nem HTTP, e não persiste nada. Quem o chama grava a demanda pelo seu repositório e o registro pela porta **`LegalCaseStatusHistoryRepository`**, de preferência na mesma transação. A implementação dessa porta, em cima de JPA, é o `LegalCaseStatusHistoryRepositoryAdapter`.
- O relógio e o gerador de identificadores são injetados, o que torna cada transição verificável com horário fixo nos testes.
- **`ReceiveLegalCaseService`** é o caso de uso de ingestão: valida os arquivos, trata a idempotência, grava demanda, histórico e metadados em uma transação só e publica o evento de recebimento. **`FindLegalCaseService`** responde à consulta de status.
- **`ProcessLegalCaseReceivedEventService`** é o caso de uso disparado pelo consumo da fila: reserva o evento, classifica a demanda, leva o status até `EXTRACTING`, gera o checklist documental, extrai o texto dos documentos e, por fim, os fatos (detalhes em [Classificação e extração de texto](#classificação-e-extração-de-texto)). Ele devolve um `LegalCaseProcessingResult` em vez de escrever no log — o módulo não depende nem de uma fachada de log, e quem conhece o contexto da mensagem é o consumidor.
- **`ExtractDocumentTextService`** extrai o texto de cada documento de uma demanda e pula os que já foram lidos.
- **`DocumentChecklistService`** gera e avalia o checklist documental e responde à consulta. **`ManageChecklistRulesService`** é o CRUD das regras. Detalhes em [Checklist documental](#checklist-documental-lexflow-application-e-lexflow-api).
- `PageQuery` e `PageResult` dão paginação às listagens sem depender do Spring Data.
- **`ExtractLegalFactsUseCase`** extrai os fatos de cada documento via LLM e leva a demanda a `AI_ANALYSIS_IN_PROGRESS` (ver [Extração de fatos](#extração-de-fatos-via-llm-lexflow-application)). `FactExtractionSchema` define o JSON Schema de cada tipo de demanda, e `PromptTemplate` monta o prompt a partir da versão registrada em `prompt_versions`.
- **`LlmClientPort`** é a porta genérica para o LLM: recebe um `LlmRequest` e devolve um `LlmResponse` já validado. Ela não conhece nenhuma regra jurídica (ver [Cliente LLM](#cliente-llm-lexflow-infrastructure)).
- As portas de saída ficam todas aqui: `LegalCaseRepository`, `DocumentRepository`, `DocumentStoragePort`, `DocumentTextExtractor`, `DocumentTextContentRepository`, `ChecklistRuleRepository`, `DocumentChecklistItemRepository`, `LlmClientPort`, `StructuredOutputValidator`, `AiExtractedFactRepository`, `PromptVersionRepository`, `LegalCaseAlertRepository`, `LegalCaseIngestionIdempotencyStore`, `ProcessingEventStore`, `LegalCaseReceivedEventPublisher` e `TransactionRunner`. A última existe para que o caso de uso possa dizer "faça isto em uma transação" sem que o Spring entre no módulo.

### Persistência (`lexflow-infrastructure`)

- **Migrations Flyway** em `src/main/resources/db/migration`. A `V1__init_schema.sql` cria as 13 tabelas da seção 6 da base de conhecimento, habilita a extensão `vector` e cria os índices, incluindo o índice vetorial HNSW em `knowledge_base_chunks(embedding)` e o índice único de `processing_events(idempotency_key)`. As migrations seguintes:

  | Migration | Prompt | Conteúdo |
  |---|---|---|
  | `V2` | 08 | coluna `legal_cases.description` |
  | `V3` | 08 | tabela `document_text_contents` |
  | `V4` | 09 | coluna `documents.document_type`, índices únicos de regras e de itens, restrições de consistência dos itens |
  | `V5` | 09 | regras iniciais do checklist (seed) |
  | `V6` | 11 | coluna `ai_extracted_facts.prompt_version_id`, um registro de fatos por documento, uma versão ativa por prompt, tabela `legal_case_alerts` e o prompt `FACT_EXTRACTION` v1 |
- **Entidades JPA** em `persistence/entity`, separadas das entidades de domínio: o módulo `lexflow-domain` não tem nenhuma anotação de persistência. A conversão entre os dois mundos fica nos mappers de `persistence/mapper`.
- **Repositórios Spring Data** em `persistence/repository`, um por tabela.
- As colunas `jsonb` usam `@JdbcTypeCode(SqlTypes.JSON)` e o `embedding` usa o tipo `vector`, através do módulo `hibernate-vector`.

Nos testes, o Hibernate roda com `ddl-auto: validate`. Se uma entidade e uma migration divergirem, o contexto nem sobe — foi assim que uma divergência de tipo de coluna apareceu já na primeira execução.

### API REST (`lexflow-api`)

| Endpoint | Descrição |
|---|---|
| `POST /api/v1/legal-cases` | Recebe uma demanda (multipart/form-data) com `caseType`, `requester`, `priority`, `externalReference`, `description` (opcional), um ou mais arquivos em `files` e, opcionalmente, `documentTypes` com o tipo de cada arquivo, na mesma ordem. Responde `201 Created` com o `id`, o `statusUrl` e o cabeçalho `Location`. |
| `GET /api/v1/legal-cases/{id}` | Status atual e metadados básicos da demanda, incluindo os arquivos anexados, o tipo de cada um e os alertas (`alerts`). |
| `GET /api/v1/legal-cases/{id}/checklist` | Itens do checklist, documentos obrigatórios que faltam e `hasSufficientDocumentation`. |
| `GET/POST /api/v1/admin/checklist-rules` | Lista (paginada, com filtro `caseType`) e cria regras de checklist. **Exige o papel `ADMIN`.** |
| `GET/PUT/DELETE /api/v1/admin/checklist-rules/{id}` | Consulta, altera e exclui uma regra. **Exige o papel `ADMIN`.** |

```bash
curl -i -X POST http://localhost:8080/api/v1/legal-cases \
  -H "Idempotency-Key: $(uuidgen)" \
  -F "caseType=CONTRACT_SIGNING" \
  -F "requester=ana.silva" \
  -F "priority=HIGH" \
  -F "description=Assinatura do contrato de licenciamento" \
  -F "files=@minuta.pdf" -F "documentTypes=CONTRACT_DRAFT" \
  -F "files=@parecer.pdf" -F "documentTypes=FINANCIAL_OPINION"

curl http://localhost:8080/api/v1/legal-cases/{id}/checklist

curl -u admin:lexflow-admin "http://localhost:8080/api/v1/admin/checklist-rules?caseType=CONTRACT_SIGNING"
```

Três decisões que valem registrar:

- **A ingestão não chama IA.** O endpoint grava a demanda e publica um evento no RabbitMQ; classificação, extração e análise acontecem depois, fora do ciclo da requisição.
- **Idempotência pelo cabeçalho `Idempotency-Key`.** A chave é reservada em `processing_events`, cujo índice único é o que de fato impede a duplicação: duas réplicas da API que recebam a mesma chave ao mesmo tempo não conseguem criar duas demandas. Um reenvio com a mesma chave devolve `201` com a demanda original, sem criar nada e sem publicar novo evento. A chave do cliente é gravada com o prefixo `legal-case-ingestion:`, para nunca colidir com a chave de uma mensagem de fila.
- **Formatos aceitos são regra de domínio,** não configuração da camada web: `DocumentFormat` aceita `pdf`, `docx`, `jpg`/`jpeg` e `png`. A extensão é que decide — um `application/octet-stream` genérico é tolerado, mas um mime type que contradiz a extensão é recusado. No banco vai sempre o tipo canônico do formato.

Os erros seguem um formato único (`code`, `message`, `timestamp`, `path`), produzido pelo `GlobalExceptionHandler` e, nos erros de autenticação, pela `SecurityConfiguration`:

| Situação | HTTP | `code` |
|---|---|---|
| Exceção de domínio, parâmetro ou corpo JSON inválido | `400` | `INVALID_REQUEST` |
| Credenciais ausentes ou erradas na API administrativa | `401` | `UNAUTHORIZED` |
| Usuário autenticado sem o papel exigido | `403` | `FORBIDDEN` |
| Demanda ou regra de checklist inexistente | `404` | `RESOURCE_NOT_FOUND` |
| Conflito de idempotência, de transição, regra duplicada ou regra em uso | `409` | `CONFLICT` |
| Arquivo acima do limite | `413` | `PAYLOAD_TOO_LARGE` |
| Storage indisponível | `503` | `STORAGE_UNAVAILABLE` |
| Qualquer outra falha | `500` | `INTERNAL_ERROR` |

Nos erros `500`, a resposta traz só uma mensagem genérica; o detalhe fica no log.

### Storage de documentos (`lexflow-infrastructure`)

O binário de cada arquivo vai para um serviço compatível com S3 — AWS S3 em produção, MinIO em desenvolvimento e nos testes —, através do `S3DocumentStorageAdapter`, que implementa a porta `DocumentStoragePort`. O banco guarda apenas o metadado, incluindo o caminho devolvido pelo adapter.

**A chave do objeto é derivada do conteúdo:** `legal-cases/{legalCaseId}/{checksum}{extensão}`. A escolha resolve três coisas de uma vez:

- **Idempotência de upload estrutural.** O mesmo arquivo reenviado para a mesma demanda cai exatamente na mesma chave, então não há um segundo objeto — e isso não depende de uma verificação que duas réplicas poderiam fazer ao mesmo tempo, ambas concluindo que o objeto não existe. Um `documentId` na chave produziria um objeto novo a cada reenvio.
- **Retenção por demanda.** O prefixo por caso mantém os objetos de uma demanda agrupados, o que sustenta uma política de expurgo por caso (seção 12).
- **Nome de arquivo é dado não confiável.** O nome escolhido pelo cliente não compõe o caminho; ele fica em `documents.file_name` e só a extensão é preservada, para o objeto continuar reconhecível ao ser inspecionado no bucket.

Nenhum log registra o conteúdo do arquivo — apenas nome, tamanho e checksum. O `retrieve` devolve o binário para o pipeline de extração (Prompt 08). Se o storage falhar, a API responde `503`, e não `500`: a causa é uma dependência externa, e o cliente pode repetir a requisição com a mesma `Idempotency-Key`.

| Propriedade (`lexflow.storage.*`) | Descrição |
|---|---|
| `bucket` | Bucket onde os documentos são gravados |
| `region` | Região informada ao SDK (o MinIO ignora, mas o SDK exige uma) |
| `endpoint` | Endereço alternativo; vazio significa o S3 da AWS |
| `access-key` / `secret-key` | Credenciais explícitas; **em branco em produção**, para o SDK usar a cadeia padrão (role da instância ou variáveis de ambiente) |
| `path-style-access` | `true` para o MinIO, que endereça o bucket no caminho da URL |
| `create-bucket-if-missing` | Cria o bucket na primeira gravação; conveniência de desenvolvimento, desligada em produção |

### Processamento assíncrono (`lexflow-infrastructure`)

A ingestão publica `LegalCaseReceivedEvent` e responde; o processamento acontece depois, em outro processo.

```
lexflow.events (topic) --[legal-case.received]--> lexflow.legal-case-received
                                                        |
                                     esgotadas as tentativas (x-dead-letter-exchange)
                                                        v
lexflow.events.dlx (topic) --[legal-case.received]--> lexflow.legal-case-received.dlq
```

**Por que RabbitMQ e não Kafka.** Para milhares de eventos por dia — poucos por minuto — os dois dão conta, então o desempate foi simplicidade operacional: a dead-letter é um argumento de fila (`x-dead-letter-exchange`) em vez de um tópico extra com error handler na aplicação; o paralelismo vem de mais réplicas, não de um número de partições decidido cedo demais; e é um broker, não um cluster com coordenação, retenção e offsets — sem nenhuma necessidade de reprocessar o histórico, que é o que justificaria o log durável do Kafka. A justificativa completa está no Javadoc de `RabbitMqConfiguration`. Se o requisito passar a incluir replay de meses de eventos, a vantagem se inverte, e a troca fica contida naquela classe e nos dois adapters ao lado.

**Idempotência.** Antes de processar, o consumidor reserva a `idempotencyKey` em `processing_events`. Um evento já concluído é descartado com log, e não como erro (seção 11); um que falhou antes é retomado na entrega seguinte; um que está em andamento em outra réplica é descartado. A garantia é do índice único no banco, não de uma consulta prévia.

**Falha e retry.** A exceção sobe do consumidor de propósito: é ela que aciona o retry com backoff exponencial (1s → 10s, 4 tentativas) e, esgotadas as tentativas, faz o broker mandar a mensagem para a dead-letter. Capturar a exceção no listener daria um "sucesso" falso e perderia a mensagem em silêncio. As transições de status ficam em uma transação só. A marca de "processado" só é gravada depois da extração de texto, e a de falha fica fora de qualquer transação, porque um rollback apagaria o próprio registro da falha. Como todas as etapas são retomáveis (ver abaixo), uma falha entre a extração e a marca de "processado" não faz nada ser repetido.

> **Limitação conhecida.** A chave de idempotência protege contra a entrega repetida do *mesmo* evento. Dois eventos *diferentes* para a mesma demanda, processados em paralelo, ainda podem ambos validar a transição em memória e gravar — não há bloqueio otimista em `legal_cases`. O fluxo atual publica exatamente um evento por demanda, então isso não é alcançável hoje; travar essa porta com uma coluna de versão é trabalho para o Prompt 17.

### Classificação e extração de texto (`lexflow-application` e `lexflow-infrastructure`)

Ao consumir o evento de recebimento, o worker leva a demanda de `RECEIVED` até `EXTRACTING`, classificando-a e gerando o checklist documental no caminho, e extrai o texto de cada documento. Nenhuma dessas etapas chama LLM.

```
RECEIVED ──► CLASSIFYING ──► EXTRACTING  (a demanda permanece aqui)
             classificação   extração de texto, nativa ou por OCR
             por palavras-chave
```

**Classificação.** O `LegalCaseKeywordClassifier` procura palavras-chave no nome de cada arquivo e na `description`, depois de tirar acentos e maiúsculas e de trocar `_`, `-` e `.` por espaço. Em cada posição vale a expressão mais longa da tabela: "acordo de confidencialidade" conta como contrato, e não como "acordo". O tipo informado pelo requisitante **nunca é trocado**. As palavras-chave só servem de validação cruzada, com um de três resultados: `CONFIRMED`, `UNCONFIRMED` (nenhuma evidência) ou `DIVERGENT` (os documentos apontam para outro tipo). O resultado vai para o motivo da transição `CLASSIFYING → EXTRACTING` no histórico, e a divergência também gera um `WARN` no log, para que um humano revise. Sem tipo informado, as palavras-chave decidem (`INFERRED`), mas só quando apontam um único tipo; com empate ou sem evidência, a demanda não é classificada. Hoje a API sempre exige o `caseType`, então esse caminho existe apenas no domínio. A tabela de palavras-chave está no código por enquanto; se a área jurídica passar a ajustá-la com frequência, ela deve ir para o banco, como as regras de checklist.

**Extração de texto.** O `TikaDocumentTextExtractor` escolhe a estratégia pelo formato:

| Formato | Estratégia | `extraction_method` |
|---|---|---|
| PDF com texto | Lê a camada de texto | `NATIVE_TEXT` |
| PDF digitalizado | Se a camada de texto tiver menos de 10 letras ou dígitos por página, em média, as páginas são renderizadas a 300 dpi e passam pelo Tesseract | `OCR` |
| DOCX | Lê a camada de texto (imagens embutidas não passam por OCR) | `NATIVE_TEXT` |
| JPEG e PNG | Tesseract | `OCR` |

O resultado vai para `document_text_contents`, com uma linha por documento, em um de três status:

- `EXTRACTED`: há texto;
- `NO_TEXT_FOUND`: o arquivo foi lido, mas não tinha texto;
- `FAILED`: o arquivo está corrompido ou protegido; a linha guarda o motivo e nenhum texto.

As falhas seguem dois caminhos, de propósito:

- **Arquivo ilegível** é problema do documento. Tentar de novo não adianta: ele é registrado como `FAILED`, e a demanda segue.
- **Tesseract ausente, tempo esgotado ou storage fora do ar** são problemas de ambiente. A exceção sobe, e a mensagem volta para a fila (retry e, depois, dead-letter).

**Retomada.** Cada etapa confere o status antes de agir, e cada documento é gravado assim que termina. Uma nova tentativa depois de uma falha na extração não reclassifica a demanda e só lê os documentos que ainda não têm texto. O OCR fica fora de qualquer transação, para não prender uma conexão do banco durante minutos.

**Por que a demanda para em `EXTRACTING`.** A seção 4 só permite sair de `EXTRACTING` para `AI_ANALYSIS_IN_PROGRESS`, e essa transição pertence à extração de fatos (Prompt 11), que consome o texto gravado aqui.

O texto dos documentos nunca vai para o log: o `toString` de `DocumentTextContent` e de `ExtractedText` mostra apenas o tamanho.

### Checklist documental (`lexflow-application` e `lexflow-api`)

É a resposta a "Esse processo tem documentação suficiente?" (`HAS_SUFFICIENT_DOCUMENTATION`), dada **só por regra de negócio**, sem LLM (seção 9 da base de conhecimento).

**Regras são configuração, não código.** Cada `ChecklistRule` diz que um tipo de demanda exige um tipo de documento, identificado por um código em `UPPER_SNAKE_CASE`, e se ele é obrigatório. A migration `V5` traz um ponto de partida; a partir daí, a área jurídica mantém as regras pela API administrativa, sem deploy.

| Tipo de demanda | Obrigatórios | Opcional |
|---|---|---|
| `SUPPLIER_HIRING` | `SUPPLIER_CNPJ_CARD`, `SUPPLIER_QUALIFICATION_DOCUMENTS` | `COMMERCIAL_PROPOSAL` |
| `CONTRACT_SIGNING` | `CONTRACT_DRAFT`, `FINANCIAL_OPINION` | `SIGNATORY_POWERS` |
| `SETTLEMENT_PAYMENT` | `SETTLEMENT_AGREEMENT`, `PAYMENT_INSTRUCTIONS` | `COURT_APPROVAL` |
| `LAWSUIT_CLOSURE` | `CLOSURE_PETITION`, `CASE_PROGRESS_REPORT` | `FINAL_JUDGMENT` |
| `PROPOSAL_ACCEPTANCE` | `PROPOSAL_DOCUMENT`, `FINANCIAL_OPINION` | `COUNTERPARTY_REGISTRATION` |

**Como o checklist é montado.**

1. **Tipo de cada documento.** Na ingestão, o requisitante informa o tipo em `documentTypes`, um valor por arquivo e na mesma ordem. O valor é normalizado para maiúsculas (`contract_draft` vira `CONTRACT_DRAFT`); um valor em branco significa "sem tipo".
2. **Geração.** Na classificação, na mesma transação que leva a demanda a `EXTRACTING`, cada regra do tipo gera um item, que nasce `MISSING`.
3. **Vínculo.** Um documento cujo tipo é o exigido pela regra torna o item `SATISFIED`. Se houver mais de um, vale o primeiro, na ordem de envio e, em caso de empate, pelo nome do arquivo.
4. **Nova sincronização.** Sincronizar de novo não duplica itens (há um índice único por demanda e regra), só regrava o que mudou e cria itens para regras adicionadas depois.
5. **Resposta.** A documentação é suficiente só quando o checklist já foi avaliado e **todo** item obrigatório está `SATISFIED`. Antes da classificação, a consulta responde `evaluated: false` e a documentação é sempre dada como insuficiente. Avançar o status da demanda não muda a resposta: ela depende só dos itens.

**Proteções da API administrativa.**

- O tipo de demanda de uma regra nunca muda.
- A mesma regra não pode existir duas vezes no mesmo tipo (`409`).
- Uma regra já usada por alguma demanda não pode ser excluída nem trocar o documento exigido (`409`); para deixar de exigir o documento, torne a regra opcional.
- Descrição e obrigatoriedade podem mudar a qualquer momento. A obrigatoriedade vale já na próxima consulta, inclusive para demandas antigas.

### Segurança (`lexflow-api`)

Só `/api/v1/admin/**` exige autenticação: HTTP Basic, com o papel `ADMIN`, sem sessão e sem CSRF. O usuário administrador vem de `LEXFLOW_ADMIN_USERNAME` e `LEXFLOW_ADMIN_PASSWORD`.

- A senha pode vir em texto ou já no formato `{bcrypt}...`; em texto, ela é transformada em hash na inicialização.
- No perfil `dev`, o padrão é `admin` / `lexflow-admin`.
- Em `prod` não há valor padrão, e a aplicação não sobe sem as duas variáveis.

> **Limitação conhecida.** As demais rotas continuam abertas. O controle de acesso por papel da seção 12 (`ANALYST`, `LEGAL_REVIEWER`, `ADMIN`) depende da escolha do provedor de identidade e fica contido na `SecurityConfiguration`.

### Extração de fatos via LLM (`lexflow-application`)

Primeiro uso real do LLM no pipeline (seção 10, item 1): o `ExtractLegalFactsUseCase` extrai **só fatos**, sem opinião jurídica, de cada documento que tem texto. Ele roda no mesmo consumidor da fila, logo depois da extração de texto.

**O que é extraído.** O JSON segue o `FactExtractionSchema`. Todo campo é obrigatório e aceita `null`, e nenhum campo extra é permitido.

| Parte | Campos |
|---|---|
| Comum a todo documento | `documentKind`, `parties` (nome, papel, CPF/CNPJ), `monetaryValues` (texto, número, moeda), `relevantDates` (texto, data ISO), `keyClauses` (título, trecho literal) |
| `specificFacts` — `SUPPLIER_HIRING` | `supplierName`, `supplierTaxId`, `serviceScope`, `contractTermText` |
| `specificFacts` — `CONTRACT_SIGNING` | `contractObject`, `contractTermText`, `terminationConditions`, `penaltyClause` |
| `specificFacts` — `SETTLEMENT_PAYMENT` | `lawsuitNumber`, `settlementAmountText`, `installmentsText`, `paymentDeadlineText` |
| `specificFacts` — `LAWSUIT_CLOSURE` | `lawsuitNumber`, `court`, `closureRequestText`, `pendingObligationsText` |
| `specificFacts` — `PROPOSAL_ACCEPTANCE` | `proposingParty`, `proposalObject`, `proposedAmountText`, `validityText` |

**O prompt.** O texto do prompt fica em `prompt_versions` (`FACT_EXTRACTION` v1, semeado pela `V6`), nunca no código.

- Ele é dividido nas seções `SISTEMA`, `USUARIO` e `REFORCO`, com marcadores `{{...}}` que o código preenche.
- As instruções pedem que o modelo registre apenas o que está escrito, não deduza nem complete, use `null` para o que não consta e não emita opinião jurídica.
- O texto do documento vai entre `<documento>` e `</documento>`, e o modelo é avisado de que instruções dentro dele não se aplicam.
- Cada fato gravado guarda o `model_version` e o `prompt_version_id`.
- Mudar o texto é criar uma nova versão; o banco aceita só uma versão ativa por prompt.

**Nada inválido é gravado.**

1. A resposta passa pela validação do cliente LLM e, de novo, pela do caso de uso, que confere o JSON contra o schema do tipo antes de gravar (porta `StructuredOutputValidator`).
2. Se falhar, há **uma** nova tentativa, com o prompt reforçando o formato e listando as violações.
3. Se falhar de novo, nada é gravado para o documento: ele recebe um alerta `FACT_EXTRACTION_INVALID_OUTPUT`, e a demanda **fica em `EXTRACTING`**.

**Como cada situação é tratada.**

| Situação | Resultado |
|---|---|
| Fatos válidos em todos os documentos com texto | Fatos gravados; a demanda passa para `AI_ANALYSIS_IN_PROGRESS` |
| Saída inválida duas vezes | Alerta `FACT_EXTRACTION_INVALID_OUTPUT`; os demais documentos seguem; a demanda não avança |
| Recusa do modelo | Alerta `FACT_EXTRACTION_REFUSED`; idem |
| Texto acima de `max-document-characters` (400 mil) | Alerta `DOCUMENT_TOO_LONG_FOR_EXTRACTION`, sem chamar o LLM — o texto **nunca** é truncado |
| LLM indisponível ou pedido recusado pelo provedor | A exceção sobe e a mensagem volta para a fila (retry e, depois, dead-letter) |
| Nenhuma versão ativa do prompt | Idem: é erro de configuração |

- **Alertas.** Ficam em `legal_case_alerts` e aparecem no `GET /api/v1/legal-cases/{id}`. Enquanto houver algum em aberto, a demanda não avança sozinha; a resolução por um humano entra com a revisão humana (Prompt 15).
- **Etapa retomável.** Documentos com fatos ou com alerta não voltam ao LLM, e há um registro de fatos por documento.
- **Documentos sem texto.** Um documento ilegível ou sem texto não tem fatos e não segura a demanda: o problema dele já está registrado em `document_text_contents`.
- **Desligar a etapa.** `LEXFLOW_FACT_EXTRACTION_ENABLED=false` desliga a extração de fatos, e a demanda para em `EXTRACTING` sem chamar o LLM. Isso é útil em ambientes sem `ANTHROPIC_API_KEY`: com a etapa ligada e sem chave, a mensagem vai para a dead-letter depois das tentativas.

### Base normativa e RAG (`lexflow-domain`, `lexflow-application` e `lexflow-infrastructure`)

A base normativa é o que permite a IA responder **citando a fonte** em vez de opinar. Este componente não decide nada juridicamente: ele divide normas em trechos, indexa e devolve os mais próximos de uma consulta. Quem responde é a cadeia de prompts (Prompt 13); quem decide é uma pessoa.

**Como uma norma entra na base.**

```bash
# Como texto
curl -u admin:lexflow-admin -X POST http://localhost:8080/api/v1/knowledge-base/sources \
  -H 'Content-Type: application/json' \
  -d '{"title":"Política de Alçadas","sourceType":"INTERNAL_POLICY","effectiveDate":"2026-01-01","text":"Art. 1º ..."}'

# Como arquivo (PDF, DOCX, imagem, .txt ou .md)
curl -u admin:lexflow-admin -X POST http://localhost:8080/api/v1/knowledge-base/sources \
  -F 'title=Lei 14.133/2021' -F 'sourceType=LAW' -F 'file=@lei-14133.pdf'

# O que a busca devolveria para uma pergunta (conferência; não chama o LLM)
curl -u admin:lexflow-admin \
  'http://localhost:8080/api/v1/knowledge-base/search?limit=5&query=assinatura+de+contrato+acima+de+cem+mil'
```

| Etapa | O que acontece |
|---|---|
| Divisão | `TextChunker`, no domínio: parágrafos, depois frases e, por último, corte no limite. Padrão de 1200 caracteres com 200 de sobreposição |
| Vetorização | `EmbeddingClientPort`, com `input_type` `document` para trechos e `query` para perguntas |
| Gravação | Fonte e trechos na mesma transação; as chamadas ao provedor ficam fora dela |
| Recuperação | Consulta nativa com o operador `<=>` do pgvector, usando o índice HNSW da migration `V1` |

- **A divisão é determinística.** Reindexar a mesma norma produz exatamente os mesmos trechos — sem isso, uma citação antiga deixaria de bater com o texto atual. Mudar a política de divisão só afeta o que for indexado a partir dali.
- **Reindexar substitui.** Os trechos antigos são apagados antes de os novos entrarem, para a mesma norma não ser recuperada em duplicata.
- **Cada trecho volta com a fonte.** Título e tipo acompanham o trecho, e não só o identificador: é assim que ele será citado ao revisor humano.
- **Trechos irrelevantes são descartados.** A busca vetorial sempre devolve os N mais próximos, mesmo quando nenhum trata do assunto. Abaixo de `min-similarity` (padrão 0,2) o trecho não vai ao modelo: norma irrelevante no contexto é convite à alucinação, e é preferível o modelo responder "informação não encontrada na base normativa".
- **Arquivos passam pelo extrator das demandas.** `.txt` e `.md` são lidos direto; PDF, DOCX e imagens usam o mesmo Tika com OCR.
- **Restrito ao papel `ADMIN`.** Indexar uma norma muda o fundamento de toda resposta futura da IA.

**Provedor de embeddings.** É uma integração separada do cliente LLM, com porta própria (`EmbeddingClientPort`): a Anthropic não expõe endpoint de embeddings, e o modelo de vetores tem ciclo de vida próprio — trocá-lo obriga a reindexar toda a base. O adapter padrão é o `VoyageEmbeddingClient` (`voyage-3-large`, 1536 dimensões), com as mesmas proteções do cliente LLM na instância `embeddings` do Resilience4j: bulkhead de 8 chamadas, 45 s por tentativa, circuito de janela 20 e retry de 3 tentativas com backoff de 1 s a 10 s. Um vetor de dimensão diferente da coluna do banco é recusado sem nova tentativa — é erro de configuração, não falha transitória.

Sem `LEXFLOW_EMBEDDINGS_API_KEY` (ou `VOYAGE_API_KEY`) a aplicação sobe normalmente; só a indexação e a recuperação falham.

### Cadeia de prompts (`lexflow-application`)

O `AnalyzeLegalCaseUseCase` é o componente mais crítico do sistema: é dele que saem as respostas que o revisor humano lê. Ele junta os fatos extraídos (Prompt 11) e a base normativa (Prompt 12) e responde, uma a uma, as perguntas aplicáveis ao tipo da demanda.

**Uma pergunta de cada vez.** Nunca há uma chamada monolítica que responda tudo. Para cada `question_key` há uma recuperação normativa própria e uma chamada própria ao modelo — perguntas diferentes se apoiam em normas diferentes, e uma resposta errada não contamina as outras.

**O que não vai ao modelo.** Duas situações são resolvidas por código e gravadas com `answer_source = DETERMINISTIC`, sem modelo e sem versão de prompt:

| Situação | Resposta |
|---|---|
| Checklist determinístico aponta documento obrigatório faltante | "Documentação incompleta: faltam os documentos obrigatórios ..." — perguntar ao modelo o que o código já sabe só criaria a chance de ele discordar |
| A recuperação não trouxe nenhum trecho próximo da pergunta | "informação não encontrada na base normativa ..." — sem contexto o modelo não teria em que se apoiar |

Com a documentação completa, `HAS_SUFFICIENT_DOCUMENTATION` **vai** ao modelo: é o caso ambíguo previsto na seção 5, do documento presente mas com conteúdo insuficiente.

**O que o prompt leva.** Os fatos extraídos de cada documento, a situação do checklist, os trechos recuperados com os seus identificadores e a lista explícita de identificadores que podem ser citados. Cada bloco vai entre marcadores (`<fatos>`, `<checklist>`, `<trechos>`), e o modelo é avisado de que instruções dentro deles não se aplicam a ele.

**Barreiras antes de gravar**, nesta ordem:

1. o JSON Schema fixo da seção 10, validado no cliente LLM e de novo no caso de uso;
2. o `question_key` respondido tem de ser o que foi perguntado;
3. **todo trecho citado tem de estar entre os que foram fornecidos** — um `chunk_id` inventado é alucinação com aparência de fundamento;
4. sem trecho citado, só passa a resposta que declara "informação não encontrada na base normativa".

Falhando, há **uma** nova tentativa com o prompt reforçado, listando as violações. Falhando de novo, nada é gravado para aquela pergunta: a demanda recebe `AI_ANALYSIS_INVALID_OUTPUT` (ou `AI_ANALYSIS_REFUSED`, se o modelo recusou) e **não avança**.

- **Rastreabilidade é restrição de banco.** A migration `V7` recusa uma resposta do modelo sem `model_version` e `prompt_version_id`, e recusa uma resposta determinística que traga qualquer um dos dois.
- **Alertas do modelo.** O campo `alerts` da resposta é anexado ao texto gravado, sob "Pontos para verificação:", em vez de ir para uma tabela à parte: são parte do que a pessoa precisa ler junto com a resposta.
- **Etapa retomável.** Uma pergunta já respondida não volta ao modelo (índice único por demanda e pergunta). Com um alerta desta etapa em aberto, nenhuma chamada nova é feita: a demanda espera tratamento humano, e repetir custaria dinheiro sem mudar o desfecho.
- **Desligar a etapa.** `LEXFLOW_LEGAL_ANALYSIS_ENABLED=false` faz a demanda parar em `AI_ANALYSIS_IN_PROGRESS`, sem consultar a base normativa nem o LLM.

### Segunda checagem (`lexflow-application`)

Uma camada extra contra a alucinação residual. Para as perguntas mais críticas — `CAN_SIGN_CONTRACT`, `CAN_PAY_SETTLEMENT` e `CAN_CLOSE_LAWSUIT` —, uma segunda chamada ao modelo confere se a resposta é mesmo sustentada pelos trechos que ela citou. Ela roda **antes** de a demanda chegar ao revisor: uma resposta reprovada precisa chegar já sinalizada.

| Veredito | O que acontece |
|---|---|
| Sustentada | `verification_status = VERIFIED`, confiança mantida, justificativa gravada |
| Não sustentada | `verification_status = FAILED`, **confiança zerada**, justificativa gravada |
| Checagem não concluída (formato inválido ou recusa) | Segue `NOT_VERIFIED`: a etapa é extra e não pode impedir a entrega ao revisor |
| Trecho citado não está mais na base (reindexação) | `FAILED`, sem chamar o modelo: a fundamentação não pôde ser conferida |

- **Ela confere, não reescreve.** O texto da resposta original nunca é alterado — a segunda checagem sinaliza e explica, e a decisão continua sendo de uma pessoa.
- **Só o que faz sentido verificar.** Respostas determinísticas (cujo fundamento é uma regra de código), respostas que declaram "informação não encontrada na base normativa" e perguntas não críticas não gastam uma chamada a mais.
- **Configurável.** `LEXFLOW_SELF_VERIFICATION_ENABLED=false` desliga a etapa, e `LEXFLOW_SELF_VERIFICATION_QUESTIONS` amplia a lista de perguntas verificadas. Desligada, as respostas chegam como `NOT_VERIFIED` — o revisor vê que a checagem não aconteceu, em vez de supor que ela passou.

### Cliente LLM (`lexflow-infrastructure`)

`AnthropicMessagesClient` implementa a `LlmClientPort` chamando `POST /v1/messages` da Anthropic por `WebClient`, como pede o Prompt 10. É um cliente HTTP genérico: não conhece demanda, pergunta jurídica nem RAG. O primeiro caso de uso a chamá-lo é a extração de fatos (Prompt 11).

```java
LlmResponse response = llmClient.complete(LlmRequest.structured(
        "Extraia apenas fatos presentes no texto.",
        textoDoDocumento,
        jsonSchema));
response.structuredOutput(); // JSON já validado contra o schema
response.model();            // modelo que respondeu, para gravar como model_version
```

**O que ele faz em cada chamada.**

- **Modelo.** O padrão é `claude-opus-5`, e o pedido pode informar outro. O modelo que efetivamente respondeu vem em `LlmResponse.model()` e é o que deve ser gravado como `model_version` (seção 10).
- **Resposta estruturada.** Quando o pedido traz um JSON Schema, ele vai em `output_config.format` (`json_schema`), e a resposta é validada de novo no cliente, com o `json-schema-validator`. O cliente recusa com `LlmResponseValidationException` uma resposta que não é JSON, que viola o schema ou que foi cortada pelo limite de tokens (`stop_reason: max_tokens`). Nada inválido segue adiante.
- **Recusa do modelo.** `stop_reason: refusal` vira `LlmRefusalException`, com a categoria informada. Por padrão, o cliente envia `fallbacks: "default"` (beta `server-side-fallback-2026-07-01`): quando um modelo recusa um pedido por política, o próprio provedor tenta outro na mesma chamada, e `servedByFallbackModel()` indica quando isso aconteceu. `LEXFLOW_LLM_REFUSAL_FALLBACK` vazio desliga o recurso.
- **Esforço de raciocínio.** `LlmRequest.effort` é o ajuste de qualidade nos modelos atuais. A `temperature` existe no contrato, mas o Claude Opus 5 recusa o parâmetro; por isso ela só é enviada quando informada.

**Proteções (Resilience4j, instância `llm`, configuradas no `application.yml`), de dentro para fora:**

| Proteção | Padrão | Papel |
|---|---|---|
| Bulkhead | 16 chamadas simultâneas por réplica, espera de até 10 s | Um provedor lento não prende todas as threads do worker |
| Time limiter | 180 s por tentativa (o HTTP corta em 190 s) | Nenhuma chamada fica pendurada |
| Circuit breaker | Janela de 20 chamadas, abre com 50% de falhas ou 80% de chamadas lentas (mais de 120 s), fica 30 s aberto | Com o provedor fora, falha na hora em vez de acumular esperas |
| Retry | 3 tentativas, backoff exponencial de 2 s a 20 s | Repete 429, 5xx, 529 (sobrecarga), timeout e falha de conexão |

**Como cada falha é tratada:**

| Exceção | Quando | Repete? | Conta para o circuito? |
|---|---|---|---|
| `LlmUnavailableException` | 408, 409, 429, 5xx, 529, timeout, conexão, circuito aberto, bulkhead cheio | Sim (menos com circuito aberto) | Sim (menos com bulkhead cheio) |
| `LlmRequestRejectedException` | Demais 4xx, ou chave de API ausente | Não | Não |
| `LlmResponseValidationException` | Resposta não é JSON, viola o schema ou veio truncada | Não | Não |
| `LlmRefusalException` | O modelo recusou | Não | Não |

Quem consome uma fila deixa a `LlmUnavailableException` subir, para a mensagem voltar mais tarde. As outras exceções pedem uma decisão do caso de uso (Prompt 11 em diante).

**Nada de conteúdo no log.** Em `INFO` saem modelo pedido e servido, `stop_reason`, tokens, latência e `request-id`. Nem em `DEBUG` o prompt é registrado: só o tamanho e um hash. O `toString` de `LlmRequest` e `LlmResponse` também omite o texto.

**Chave de API.** Vem de `ANTHROPIC_API_KEY`. Sem ela, a aplicação sobe normalmente e só as chamadas ao LLM falham, com `LlmRequestRejectedException`.

## Como executar

Pré-requisitos:

- um JDK instalado para rodar o Gradle; se o Java 21 não estiver disponível, o toolchain do Gradle baixa essa versão automaticamente;
- o Docker em execução;
- o **Tesseract** instalado, com o pacote de idioma português: `brew install tesseract tesseract-lang` no macOS, ou `apt-get install tesseract-ocr tesseract-ocr-por` no Debian e no Ubuntu.

Sem o Tesseract, a aplicação sobe e registra um aviso no log. PDFs com texto e arquivos DOCX continuam sendo processados, mas imagens e PDFs digitalizados falham até que ele seja instalado. A imagem de produção precisa trazer o Tesseract e os pacotes de idioma configurados.

```bash
# Sobe o PostgreSQL com pgvector, o MinIO (com o bucket já criado) e o RabbitMQ
docker compose up -d

# Compila todos os módulos, roda os testes e verifica a cobertura
./gradlew build

# Sobe a aplicação (o perfil padrão é o dev); o Flyway aplica as migrations na inicialização
./gradlew :lexflow-api:bootRun

# Health check
curl http://localhost:8080/actuator/health

# Regras de checklist (usuário do perfil dev)
curl -u admin:lexflow-admin http://localhost:8080/api/v1/admin/checklist-rules
```

Para encerrar os serviços locais: `docker compose stop` (ou `docker compose down -v`, que também apaga os dados). O console do MinIO fica em `http://localhost:9001` (usuário `lexflow`, senha `lexflow123`) e o do RabbitMQ em `http://localhost:15672` (usuário e senha `lexflow`).

## Testes

```bash
./gradlew test                          # todos os módulos
./gradlew :lexflow-domain:test          # testes unitários, não precisam de Docker
./gradlew :lexflow-application:test     # testes unitários, não precisam de Docker
./gradlew :lexflow-infrastructure:test  # testes de integração, precisam de Docker
./gradlew :lexflow-api:test             # testes de integração da API, precisam de Docker
```

Os testes de integração sobem um PostgreSQL com pgvector, um MinIO e um RabbitMQ via Testcontainers, aplicam as migrations e validam o mapeamento das entidades contra o schema real. Como as classes herdam de uma base comum — `AbstractPersistenceIT` na infraestrutura e `AbstractApiIT` na API —, o Spring reaproveita o contexto e os containers entre elas.

O checklist é coberto em três níveis:

- **domínio** (`DocumentChecklistTest`): geração, vínculo e suficiência;
- **aplicação** (`DocumentChecklistServiceTest` e `ManageChecklistRulesServiceTest`): cada tipo de demanda e as proteções das regras;
- **integração**: `ChecklistPersistenceIT` testa o seed e as restrições no banco, `ChecklistRuleAdminIT` testa o CRUD com autenticação real e `LegalCaseChecklistIT` testa o fluxo de ponta a ponta, com documentação completa e incompleta.

Os testes administrativos usam códigos próprios (`IT_...`) e removem as regras que criam, porque o banco é compartilhado com os demais testes da API.

O cliente LLM é testado em `AnthropicMessagesClientIT`, contra um provedor simulado com **WireMock**. O teste não precisa de Docker nem de chave de API. Ele sobe só o cliente e a autoconfiguração real do Resilience4j, com tempos curtos, e cobre:

- resposta válida, incluindo cabeçalhos e corpo enviados;
- JSON malformado, schema violado e resposta truncada;
- recusa do modelo;
- 5xx seguido de sucesso, 529 e 429 persistentes, e 400 sem nova tentativa;
- timeout e queda de conexão;
- o circuito abrindo, falhando sem chamar o provedor e fechando quando ele volta;
- o bulkhead isolando uma chamada lenta das demais.

O `LexFlowApplicationTest` confere que a configuração do `application.yml` foi carregada.

Nos testes da API, o `LlmClientPort` é sempre o `StubLlmClient`: nenhum teste automatizado chama o provedor real.

- **Resposta padrão.** O dublê devolve o gabarito (`fixtures/facts/contrato-texto-nativo.gabarito.json`) para o contrato de teste e uma extração vazia para os demais documentos.
- **Respostas roteirizadas.** Cada teste pode enfileirar respostas próprias.
- **Critério de aceite.** O `LegalCaseFactExtractionIT` confere que os fatos gravados do contrato batem com o gabarito e que uma extração malformada nunca é gravada, gerando o alerta.
- **Demais níveis.** A lógica é coberta sem Docker em `ExtractLegalFactsUseCaseTest`. O `FactExtractionSchemaValidationTest` confere o schema e o gabarito com o validador real, e o `FactExtractionPersistenceIT` confere o prompt semeado e as restrições no banco.

A segunda checagem é coberta em `VerifyAiAnalysisResponseUseCaseTest` (os dois vereditos, a checagem inconclusiva, o trecho que sumiu da base e as respostas que não devem ser verificadas) e, de ponta a ponta, em `LegalCaseAnalysisIT`, onde o critério de aceite do Prompt 14 é uma resposta deliberadamente não sustentada virando `FAILED` com confiança zerada.

A cadeia de prompts é coberta em dois níveis:

- **aplicação** (`AnalyzeLegalCaseUseCaseTest`): com LLM roteirizado e base normativa em memória, cobre a resposta de todas as perguntas, as duas respostas determinísticas, a recusa de citação inventada, a nova tentativa reforçada, os alertas e a retomada — tudo sem Docker;
- **integração** (`LegalCaseAnalysisIT`): o critério de aceite do Prompt 13 de ponta a ponta — norma indexada pela API, demanda ingerida, e todas as `question_key` do tipo respondidas com a demanda em `PENDING_HUMAN_REVIEW`. O `LegalAnalysisPersistenceIT` confere as restrições da `V7` contra o PostgreSQL real.

Nos testes da API o `StubLlmClient` reconhece a etapa pelo schema recebido e, na análise, responde citando o primeiro trecho que lhe foi oferecido — um modelo bem-comportado nunca cita um identificador que não recebeu.

A base normativa é coberta em três níveis, e **nenhum teste chama o provedor real de embeddings**:

- **domínio** (`TextChunkerTest`, `EmbeddingTest`): a divisão respeita o limite, mantém a ordem, se sobrepõe e é determinística; a similaridade de cosseno e a imutabilidade do vetor;
- **aplicação** (`KnowledgeBaseRagTest`): indexação, reindexação, corte por similaridade e recuperação, sem Docker;
- **integração**: `KnowledgeBaseRetrievalIT` roda o critério de aceite do Prompt 12 contra o PostgreSQL real — três normas indexadas, e a consulta relacionada a uma delas traz os trechos certos entre os três primeiros. `VoyageEmbeddingClientIT` testa o cliente contra o WireMock (lotes, ordem, retry de 429, recusa de 400 e dimensão inesperada), e `KnowledgeBaseIT` cobre os endpoints com autenticação real.

Nos testes, o modelo de embeddings é o `LexicalEmbeddingClient`: cada palavra soma um em uma dimensão do vetor. Não capta sinônimo nem paráfrase, mas reproduz a única propriedade de que a recuperação depende — textos sobre o mesmo assunto ficam próximos — e, sendo determinístico, o resultado do teste não depende de rede nem de chave de API.

Os testes de extração (`TikaDocumentTextExtractorTest`, `LegalCaseClassificationExtractionIT` e `LegalCaseChecklistIT`) **exigem o Tesseract instalado**. Sem ele, os testes falham em vez de serem pulados: um OCR quebrado precisa aparecer no build. As fixtures ficam em `lexflow-infrastructure/src/testFixtures/resources/fixtures/documents`, todas com conteúdo fictício: um PDF com texto, um PDF digitalizado, um PNG, um JPEG e um DOCX. Elas são descritas em `DocumentFixtures` e compartilhadas com o módulo da API.

O adapter de storage é testado contra um MinIO real, e não contra um dublê do S3: erros de *path-style access*, de credencial e de bucket inexistente só aparecem contra um serviço de verdade.

O módulo `lexflow-infrastructure` publica *test fixtures* com a classe `PostgresTestcontainersConfiguration`, que os testes de qualquer módulo podem importar:

```java
@SpringBootTest
@Import({
    PostgresTestcontainersConfiguration.class,
    MinioTestcontainersConfiguration.class,
    RabbitMqTestcontainersConfiguration.class
})
class MeuTesteDeIntegracao { }
```

Nos testes o consumidor da fila sobe **parado**, e cada teste o inicia quando o cenário está montado. Sem isso, o consumo em segundo plano correria contra as asserções de qualquer teste que crie uma demanda, e o status observado dependeria de quem chegasse primeiro ao banco.

> As imagens do MinIO vêm do `quay.io`, e não do Docker Hub: o repositório `minio/minio` do Hub deixou de ser público.

### Cobertura

Os módulos `lexflow-domain` e `lexflow-application` exigem no mínimo 80% de cobertura de linha, conforme a seção 13 da base de conhecimento. A regra é configurada uma única vez, no `build.gradle.kts` da raiz. A verificação roda dentro do `./gradlew build` e quebra o build se a cobertura cair. Os relatórios em HTML ficam em `<módulo>/build/reports/jacoco/test/html/index.html`.

## Perfis e variáveis de ambiente

O arquivo de configuração é `lexflow-api/src/main/resources/application.yml`.

| Perfil | Uso |
|---|---|
| `dev` | Perfil padrão. Aponta para o banco do `compose.yaml` e mostra os detalhes do health check. |
| `prod` | Ative com `SPRING_PROFILES_ACTIVE=prod`. As credenciais vêm só de variáveis de ambiente, sem valores de fallback. |

| Variável | Descrição |
|---|---|
| `LEXFLOW_SERVER_PORT` | Porta HTTP (padrão `8080`) |
| `LEXFLOW_DB_URL` | URL JDBC do PostgreSQL |
| `LEXFLOW_DB_USERNAME` | Usuário do banco |
| `LEXFLOW_DB_PASSWORD` | Senha do banco |
| `LEXFLOW_MAX_FILE_SIZE` | Tamanho máximo de cada arquivo enviado (padrão `25MB`) |
| `LEXFLOW_MAX_REQUEST_SIZE` | Tamanho máximo do multipart inteiro (padrão `100MB`) |
| `LEXFLOW_STORAGE_BUCKET` | Bucket dos documentos (padrão `lexflow-documents`) |
| `LEXFLOW_STORAGE_REGION` | Região do SDK (padrão `us-east-1`) |
| `LEXFLOW_STORAGE_ENDPOINT` | Endpoint alternativo; vazio aponta para o S3 da AWS |
| `LEXFLOW_STORAGE_ACCESS_KEY` | Credencial do storage; em branco usa a cadeia padrão do SDK |
| `LEXFLOW_STORAGE_SECRET_KEY` | Credencial do storage; em branco usa a cadeia padrão do SDK |
| `LEXFLOW_STORAGE_PATH_STYLE` | `true` para MinIO (padrão `false`) |
| `LEXFLOW_RABBITMQ_HOST` / `_PORT` | Endereço do broker (padrão `localhost:5672`) |
| `LEXFLOW_RABBITMQ_USERNAME` / `_PASSWORD` | Credenciais do broker |
| `LEXFLOW_RABBITMQ_VHOST` | Virtual host (padrão `/`) |
| `LEXFLOW_RABBITMQ_CONCURRENCY` / `_MAX_CONCURRENCY` | Consumidores por réplica (padrão `2`–`8`) |
| `LEXFLOW_RABBITMQ_MAX_ATTEMPTS` | Tentativas antes da dead-letter (padrão `4`) |
| `LEXFLOW_ADMIN_USERNAME` | Usuário da API administrativa (padrão `admin` no perfil `dev`; obrigatório em `prod`) |
| `LEXFLOW_ADMIN_PASSWORD` | Senha da API administrativa, em texto ou `{bcrypt}...` (padrão `lexflow-admin` no perfil `dev`; obrigatória em `prod`) |
| `ANTHROPIC_API_KEY` | Chave da API da Anthropic; sem ela, as chamadas ao LLM falham, mas a aplicação sobe |
| `LEXFLOW_LLM_MODEL` | Modelo padrão (padrão `claude-opus-5`) |
| `LEXFLOW_LLM_MAX_TOKENS` | Limite de tokens padrão por resposta (padrão `16000`) |
| `LEXFLOW_LLM_BASE_URL` | Endereço da API (padrão `https://api.anthropic.com`) |
| `LEXFLOW_LLM_TIMEOUT` / `LEXFLOW_LLM_RESPONSE_TIMEOUT` | Tempo máximo por tentativa no time limiter e na camada HTTP (padrão `180s` / `190s`) |
| `LEXFLOW_LLM_MAX_ATTEMPTS` | Tentativas por chamada (padrão `3`) |
| `LEXFLOW_LLM_CIRCUIT_OPEN_WAIT` | Tempo com o circuito aberto (padrão `30s`) |
| `LEXFLOW_LLM_MAX_CONCURRENT_CALLS` | Chamadas simultâneas ao LLM por réplica (padrão `16`) |
| `LEXFLOW_LLM_REFUSAL_FALLBACK` | `default` para o provedor tentar outro modelo quando o pedido é recusado; vazio desliga |
| `LEXFLOW_EMBEDDINGS_API_KEY` / `VOYAGE_API_KEY` | Chave do provedor de embeddings; sem ela, a indexação e a recuperação falham, mas a aplicação sobe |
| `LEXFLOW_EMBEDDINGS_BASE_URL` | Endereço do provedor (padrão `https://api.voyageai.com`) |
| `LEXFLOW_EMBEDDINGS_MODEL` | Modelo de embeddings (padrão `voyage-3-large`) |
| `LEXFLOW_EMBEDDINGS_BATCH_SIZE` | Trechos por chamada ao provedor (padrão `32`) |
| `LEXFLOW_EMBEDDINGS_TIMEOUT` / `_RESPONSE_TIMEOUT` | Tempo máximo por tentativa e na camada HTTP (padrão `45s` / `60s`) |
| `LEXFLOW_EMBEDDINGS_MAX_ATTEMPTS` | Tentativas por chamada (padrão `3`) |
| `LEXFLOW_EMBEDDINGS_MAX_CONCURRENT_CALLS` | Chamadas simultâneas por réplica (padrão `8`) |
| `LEXFLOW_KB_CHUNK_MAX_CHARACTERS` / `_OVERLAP` | Tamanho e sobreposição dos trechos normativos (padrão `1200` / `200`) |
| `LEXFLOW_KB_RETRIEVAL_LIMIT` | Trechos recuperados por pergunta jurídica (padrão `5`) |
| `LEXFLOW_KB_MIN_SIMILARITY` | Similaridade mínima para um trecho ser levado ao modelo (padrão `0.2`) |
| `LEXFLOW_FACT_EXTRACTION_ENABLED` | Liga a extração de fatos via LLM (padrão `true`); desligada, a demanda para em `EXTRACTING` |
| `LEXFLOW_FACT_EXTRACTION_MAX_CHARACTERS` | Tamanho máximo do texto enviado por documento (padrão `400000`); acima disso, alerta |
| `LEXFLOW_LEGAL_ANALYSIS_ENABLED` | Liga a cadeia de prompts (padrão `true`); desligada, a demanda para em `AI_ANALYSIS_IN_PROGRESS` |
| `LEXFLOW_SELF_VERIFICATION_ENABLED` | Liga a segunda checagem das respostas críticas (padrão `true`) |
| `LEXFLOW_SELF_VERIFICATION_QUESTIONS` | Perguntas verificadas, separadas por vírgula; em branco usa as três críticas da seção 10 |
| `LEXFLOW_OCR_LANGUAGE` | Idiomas do Tesseract, no formato dele (padrão `por`; ex.: `por+eng`) |
| `LEXFLOW_TESSERACT_PATH` | Diretório do executável `tesseract`; vazio procura no `PATH` |
| `LEXFLOW_OCR_TIMEOUT` | Tempo máximo de OCR por imagem ou página (padrão `2m`) |
| `LEXFLOW_OCR_DPI` | Resolução da renderização de PDFs digitalizados (padrão `300`) |

## Convenções

- Identificadores (classes, pacotes, tabelas, colunas, enums) em **inglês**; comentários e Javadoc em **português**.
- Enums e status em `UPPER_SNAKE_CASE`; tabelas no plural e colunas no singular, em `snake_case`.
- Toda exceção de domínio estende `DomainException` e tem nome descritivo.
- Nenhuma regra de negócio na camada de persistência, e nenhuma anotação de framework no domínio.
- As versões das dependências ficam centralizadas em `gradle/libs.versions.toml`.

## Documentação

- `docs/00-knowledge-base.md`: base de conhecimento do sistema
- `files/`: prompts de implementação, de `01` a `19`, a serem executados em ordem
