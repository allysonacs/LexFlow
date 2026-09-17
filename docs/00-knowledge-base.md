# LexFlow — Base de Conhecimento do Sistema

> **Como usar este arquivo:** este documento é a fonte única de verdade do domínio, das regras de negócio, das convenções e da arquitetura do projeto **LexFlow**. Ele deve ser colocado em `docs/00-knowledge-base.md` na raiz do repositório. Todo prompt enviado ao Claude Code (arquivos `01-*.md` a `19-*.md`) referencia este documento como contexto obrigatório. Não execute nada a partir deste arquivo isoladamente — ele é apenas conhecimento de base.

---

## 1. Visão geral do negócio

A empresa recebe milhares de demandas jurídicas por dia (contratos, acordos, propostas, processos judiciais). Hoje a análise é manual, lenta e inconsistente. O **LexFlow** automatiza a primeira camada de análise:

1. Recebe a documentação de uma demanda jurídica.
2. Classifica o tipo de demanda.
3. Verifica se a documentação obrigatória está completa (checklist determinístico).
4. Usa um LLM, com apoio de uma base normativa (RAG), para responder um conjunto de perguntas jurídicas sobre a demanda.
5. Apresenta as respostas da IA — sempre com a fonte citada e um nível de confiança — para um responsável humano.
6. O responsável decide: **APPROVED**, **REJECTED** ou **RETURNED_FOR_CORRECTION**.
7. A decisão dispara ações (notificação, atualização de status) e tudo fica registrado em auditoria.

**Princípio inegociável:** a IA nunca decide sozinha. Ela instrui e cita fontes; o humano decide. Tudo que for regra determinística (ex.: documento obrigatório presente ou não) deve ser resolvido por código, não por LLM.

---

## 2. Nome do projeto e convenções

- **Nome do projeto:** `LexFlow`
- **Pacote raiz Java:** `com.lexflow`
- **Repositório/módulos Gradle:** `lexflow-domain`, `lexflow-application`, `lexflow-infrastructure`, `lexflow-api`
- **Idioma de identificadores (classes, pacotes, tabelas, colunas, enums, tópicos de fila):** **inglês**
- **Idioma de comentários e Javadoc:** **português**
- **Idioma de mensagens de commit:** livre, mas recomenda-se português para descrição e inglês para o tipo (`feat:`, `fix:`, `chore:`)
- **Formato de status/enum:** `UPPER_SNAKE_CASE`
- **Formato de tabelas/colunas:** `snake_case`, sempre no singular para colunas e plural para tabelas (`legal_cases`, não `legal_case`)

---

## 3. Glossário de domínio

| Termo em português | Nome no sistema (inglês) | Descrição |
|---|---|---|
| Demanda jurídica | `LegalCase` | Unidade central de trabalho: uma solicitação que precisa de análise jurídica |
| Documento anexado | `Document` | Arquivo enviado como parte de uma demanda |
| Regra de checklist | `ChecklistRule` | Regra configurável: quais documentos são obrigatórios para cada tipo de demanda |
| Item de checklist | `DocumentChecklistItem` | Instância de uma regra aplicada a uma demanda específica, com status |
| Fato extraído | `AiExtractedFact` | Dados estruturados extraídos de um documento pela IA (sem opinião jurídica) |
| Fonte normativa | `KnowledgeBaseSource` | Um documento de legislação/política interna indexado para RAG |
| Trecho normativo | `KnowledgeBaseChunk` | Pedaço de uma fonte normativa, com embedding, usado na recuperação (RAG) |
| Resposta da IA | `AiAnalysisResponse` | Resposta estruturada da IA a uma pergunta jurídica específica, com fonte citada e confiança |
| Versão de prompt | `PromptVersion` | Registro versionado do template de prompt usado, para rastreabilidade |
| Decisão humana | `Decision` | Decisão final do responsável: aprovar, reprovar ou devolver |
| Texto extraído | `DocumentTextContent` | Texto de um documento, obtido da camada de texto ou por OCR, sem nenhum uso de LLM (Prompt 08) |
| Classificação da demanda | `LegalCaseClassification` | Resultado da validação do tipo de demanda por palavras-chave (seção 5.1) |
| Alerta da demanda | `LegalCaseAlert` | Situação que o pipeline não resolve sozinho (ex.: fatos inválidos após nova tentativa); segura a demanda até um humano tratar |
| Log de auditoria | `AuditLog` | Registro imutável de qualquer ação relevante no sistema |
| Evento de processamento | `ProcessingEvent` | Registro de evento assíncrono, usado para garantir idempotência |

---

## 4. Máquina de estados (`LegalCaseStatus`)

```
RECEIVED
  → CLASSIFYING
  → EXTRACTING
  → AI_ANALYSIS_IN_PROGRESS
  → PENDING_HUMAN_REVIEW
  → APPROVED | REJECTED | RETURNED_FOR_CORRECTION
      (RETURNED_FOR_CORRECTION volta para RECEIVED após reenvio de documentação)
  → CLOSED (estado terminal, após APPROVED ou REJECTED serem processados)
```

Regras:
- Transições inválidas devem lançar uma exceção de domínio (`InvalidStatusTransitionException`).
- Toda transição gera uma linha em `legal_case_status_history`.
- Nenhuma camada além da state machine do domínio pode alterar o status diretamente.
- Na aplicação, o ponto único de mudança de status é o `LegalCaseStatusTransitionService`: ele valida a transição e devolve, na mesma operação, a demanda atualizada e a linha de histórico, que quem chama grava na mesma transação.
- `RECEIVED` é o estado inicial e também gera uma linha de histórico, com `previous_status` nulo.

Responsável por cada transição:

| Transição | Quem executa |
|---|---|
| criação em `RECEIVED` | ingestão (`POST /api/v1/legal-cases`, Prompt 05) |
| `RECEIVED → CLASSIFYING → EXTRACTING` | consumidor da fila, na mesma transação da classificação e da geração do checklist (Prompts 07, 08 e 09) |
| `EXTRACTING → AI_ANALYSIS_IN_PROGRESS` | extração estruturada de fatos (Prompt 11), no mesmo consumidor da fila, quando nenhum alerta está em aberto. Com alerta, a demanda permanece em `EXTRACTING` |
| `AI_ANALYSIS_IN_PROGRESS → PENDING_HUMAN_REVIEW` e seguintes | cadeia de IA e revisão humana (Prompts 13 e 15) |

---

## 5. Tipos de demanda (`LegalCaseType`) e perguntas jurídicas associadas

| `LegalCaseType` | Pergunta original (PT-BR) | `question_key` |
|---|---|---|
| `SUPPLIER_HIRING` | "Podemos contratar esse fornecedor?" | `CAN_HIRE_SUPPLIER` |
| `CONTRACT_SIGNING` | "Podemos assinar esse contrato?" | `CAN_SIGN_CONTRACT` |
| `SETTLEMENT_PAYMENT` | "Podemos pagar esse acordo?" | `CAN_PAY_SETTLEMENT` |
| `LAWSUIT_CLOSURE` | "Essa ação judicial pode ser encerrada?" | `CAN_CLOSE_LAWSUIT` |
| `PROPOSAL_ACCEPTANCE` | "Podemos aceitar essa proposta?" | `CAN_ACCEPT_PROPOSAL` |
| *(todos os tipos)* | "Esse processo tem documentação suficiente?" | `HAS_SUFFICIENT_DOCUMENTATION` |
| *(todos os tipos)* | "Essa demanda está de acordo com a legislação e com a política da empresa?" | `COMPLIES_WITH_LAW_AND_POLICY` |

`HAS_SUFFICIENT_DOCUMENTATION` é resolvida primariamente pelo checklist determinístico (seção 9), não pelo LLM — a IA só entra para casos ambíguos (documento presente mas com conteúdo insuficiente).

Novos tipos de demanda devem ser adicionados a este glossário antes de implementados.

### 5.1 Classificação do tipo de demanda (Prompt 08)

A classificação é determinística, por palavras-chave, e **nunca usa LLM**.

- **Sinais:** o nome de cada arquivo e a `description` opcional da demanda. Os documentos em si não entram, porque o texto só é extraído depois da classificação.
- **Normalização:** o texto vai para minúsculas, perde os acentos e tem os separadores (`_`, `-`, `.`) trocados por espaço. A busca é por palavra inteira.
- **Expressão mais longa prevalece:** "acordo de confidencialidade" indica `CONTRACT_SIGNING`, e não a palavra "acordo", que indicaria `SETTLEMENT_PAYMENT`.
- **Pontuação:** cada ocorrência soma um ponto para o tipo correspondente. Uma mesma expressão não pode apontar para dois tipos.
- **Tipo informado pelo requisitante:** é o tipo que vale, e **nunca é substituído**. As palavras-chave servem só de validação cruzada:
  - `CONFIRMED`: o tipo informado está entre os mais pontuados;
  - `UNCONFIRMED`: nenhuma palavra-chave foi encontrada;
  - `DIVERGENT`: as palavras-chave apontam para outro tipo. A divergência é registrada no motivo da transição `CLASSIFYING → EXTRACTING` e sinalizada para revisão humana.
- **Tipo não informado:** as palavras-chave decidem (`INFERRED`), mas só quando apontam um único tipo. Com empate ou sem evidência, a demanda não é classificada (`LegalCaseTypeNotClassifiableException`), porque um palpite geraria um checklist errado. Hoje a API exige o `caseType`, então esse caminho existe apenas no domínio.
- **Onde fica a tabela:** em `LegalCaseKeywordClassifier`. Ela muda junto com este glossário. Se a área jurídica passar a ajustá-la com frequência, deve migrar para o banco, como as regras de checklist.

### 5.2 Formatos de documento aceitos (Prompt 05)

`DocumentFormat` é regra de domínio e aceita `pdf`, `docx`, `jpg`/`jpeg` e `png`.

- Quem decide o formato é a extensão do arquivo.
- Um mime type genérico (`application/octet-stream`) é tolerado; um mime type que contradiz a extensão é recusado.
- Em `documents.mime_type` vai sempre o tipo canônico do formato.
- Ampliar a lista é uma decisão de negócio.

---

## 6. Modelo de dados (nomes de tabela em inglês)

```sql
legal_cases (
  id, external_reference, case_type, status,
  requester, description, priority, created_at, updated_at
)
-- description: texto livre opcional do requisitante (até 2000 caracteres), sinal da classificação (Prompt 08)
-- priority: LOW | NORMAL | HIGH | URGENT (padrão NORMAL)

legal_case_status_history (
  id, legal_case_id, previous_status, new_status,
  changed_at, changed_by, reason
)

documents (
  id, legal_case_id, file_name, storage_path, mime_type,
  checksum_sha256, uploaded_at, document_type
)
-- único por (legal_case_id, checksum_sha256): o mesmo arquivo não é anexado duas vezes à mesma demanda
-- document_type: código opcional informado no upload (ex.: CONTRACT_DRAFT); vincula o documento ao checklist

document_text_contents (
  id, document_id (unique), legal_case_id, content, extraction_method,
  status, failure_reason, extracted_at
)
-- extraction_method: NATIVE_TEXT | OCR (nulo quando status = FAILED)
-- status: EXTRACTED | NO_TEXT_FOUND | FAILED
-- FAILED não tem content e exige failure_reason; os demais têm content (vazio em NO_TEXT_FOUND)

checklist_rules (
  id, case_type, required_document_type, description, mandatory
)
-- único por (case_type, required_document_type); required_document_type em UPPER_SNAKE_CASE

document_checklist_items (
  id, legal_case_id, checklist_rule_id, status, document_id, evaluated_at
)
-- status: PENDING | SATISFIED | MISSING
-- único por (legal_case_id, checklist_rule_id); document_id preenchido se, e somente se, status = SATISFIED

ai_extracted_facts (
  id, legal_case_id, document_id, extracted_json, model_version, prompt_version_id, extracted_at
)
-- único por document_id; prompt_version_id obrigatório (seção 10, item 5)

knowledge_base_sources (
  id, title, source_type, effective_date
)

knowledge_base_chunks (
  id, source_id, chunk_index, content, embedding
)

ai_analysis_responses (
  id, legal_case_id, question_key, answer_text, confidence_score,
  cited_chunks, model_version, prompt_version_id, created_at
)

prompt_versions (
  id, prompt_key, version, template_text, active, created_at
)
-- no máximo uma versão ativa por prompt_key; uma versão nunca é editada, só substituída por outra

legal_case_alerts (
  id, legal_case_id, document_id, alert_type, message, created_at, resolved_at
)
-- alert_type: FACT_EXTRACTION_INVALID_OUTPUT | FACT_EXTRACTION_REFUSED | DOCUMENT_TOO_LONG_FOR_EXTRACTION
-- alerta aberto (resolved_at nulo) impede a demanda de avançar automaticamente

decisions (
  id, legal_case_id, decision_type, decided_by, decided_at, comments
)
-- decision_type: APPROVED | REJECTED | RETURNED_FOR_CORRECTION

audit_logs (
  id, entity_type, entity_id, action, actor, payload, occurred_at
)

processing_events (
  id, event_type, aggregate_id, idempotency_key (unique), status,
  payload, created_at, processed_at
)
```

`embedding` usa o tipo `vector` da extensão `pgvector` (dimensão 1536, com índice HNSW por similaridade de cosseno). `extracted_json`, `cited_chunks` e `payload` são colunas `jsonb`.

Migrations Flyway em `lexflow-infrastructure/src/main/resources/db/migration`:

| Migration | Conteúdo |
|---|---|
| `V1__init_schema.sql` | as 13 tabelas originais desta seção, a extensão `vector` e os índices |
| `V2__add_legal_case_description.sql` | coluna `legal_cases.description` (Prompt 08) |
| `V3__create_document_text_contents.sql` | tabela `document_text_contents`, com as restrições de consistência (Prompt 08) |
| `V4__checklist_constraints_and_document_type.sql` | coluna `documents.document_type`, índices únicos de regras e de itens e restrições dos itens (Prompt 09) |
| `V5__seed_checklist_rules.sql` | regras iniciais do checklist, com identificadores fixos (Prompt 09) |
| `V6__fact_extraction.sql` | `ai_extracted_facts.prompt_version_id`, índices únicos, `legal_case_alerts` e o prompt `FACT_EXTRACTION` v1 (Prompt 11) |

Uma migration aplicada nunca é editada: toda mudança de schema entra em uma migration nova, e esta seção deve ser atualizada junto.

---

## 7. Arquitetura e módulos

Arquitetura hexagonal (ports & adapters), não MVC tradicional:

```
lexflow-domain          → entidades, value objects, regras puras, state machine
lexflow-application     → casos de uso (use cases), portas (interfaces)
lexflow-infrastructure  → adapters: JPA/Postgres, fila, storage S3/MinIO,
                          cliente LLM, RAG/pgvector
lexflow-api             → controllers REST, DTOs, tratamento de erros
```

Regra: `domain` não depende de nenhum framework. `application` depende só de `domain`. `infrastructure` e `api` implementam as portas definidas em `application`.

---

## 8. Stack tecnológica

- **Linguagem/Runtime:** Java 21 (virtual threads habilitadas)
- **Framework:** Spring Boot 3.x
- **Build:** Gradle (Kotlin DSL), multi-módulo
- **Banco relacional:** PostgreSQL + extensão `pgvector`
- **Migrations:** Flyway
- **Mensageria:** RabbitMQ (via Spring AMQP), escolhido no Prompt 07 por simplicidade operacional. A justificativa está na seção 14.
- **Storage de arquivos:** S3 em produção e MinIO em desenvolvimento e nos testes, via AWS SDK v2
- **Extração de texto:** Apache Tika 3.x, com o Tesseract para OCR (idioma `por`). O Tesseract é um executável externo e precisa estar instalado no ambiente de execução.
- **Cliente HTTP para LLM:** `WebClient` (Spring WebFlux, só como cliente; o servidor continua sendo o Tomcat), chamando a Messages API da Anthropic (`POST /v1/messages`). O modelo padrão é `claude-opus-5`.
- **Resiliência:** Resilience4j (circuit breaker, retry, timeout, bulkhead), configurado em `resilience4j.*.instances.<nome>` no `application.yml`
- **Observabilidade:** Micrometer + OpenTelemetry
- **Testes:** JUnit 5, AssertJ, Awaitility e Testcontainers (PostgreSQL com pgvector, RabbitMQ, MinIO). Os testes de OCR exigem o Tesseract instalado.
- **Cobertura:** JaCoCo, com a verificação de 80% de `domain` e `application` rodando no `./gradlew build`

---

## 9. Regras de negócio do checklist documental

- Cada `LegalCaseType` tem um conjunto de `ChecklistRule` (configurável em banco, não hardcoded).
- Ao classificar uma demanda, o sistema gera os `DocumentChecklistItem` correspondentes às regras do tipo.
- Um item fica `MISSING` até que um documento do tipo exigido seja vinculado; então passa a `SATISFIED`.
- `HAS_SUFFICIENT_DOCUMENTATION` só pode ser respondida como suficiente se **todos** os itens obrigatórios (`mandatory = true`) estiverem `SATISFIED`.
- Regras devem ser avaliadas de forma **determinística e testável isoladamente**, sem chamar o LLM.

Detalhamento (Prompt 09):

- **Tipo de documento.** É um código em `UPPER_SNAKE_CASE` (`DocumentTypeCode`), com até 100 caracteres. O requisitante informa o tipo de cada arquivo no upload, e a regra informa o tipo exigido; o vínculo é por igualdade exata, depois da normalização para maiúsculas. Um documento sem tipo não satisfaz regra nenhuma.
- **Geração.** Os itens são gerados na classificação, na mesma transação da transição `CLASSIFYING → EXTRACTING`, e nascem `MISSING`. Novas sincronizações não duplicam itens e criam itens para regras adicionadas depois. `PENDING` só existe para itens criados sem avaliação.
- **Vínculo.** Havendo mais de um documento do tipo exigido, vale o primeiro, na ordem de envio, com desempate por nome e identificador. Se o documento deixar de existir, o item volta a `MISSING`.
- **Suficiência.** Um checklist sem itens é suficiente, porque o tipo não exige nada. A consulta, porém, só responde "suficiente" depois que a demanda passou pela classificação (`evaluated`); antes disso, a resposta é sempre negativa. O status posterior da demanda não altera a resposta.
- **Etapas com IA.** A IA pode apontar insuficiência de conteúdo em um documento presente, mas nunca transformar em suficiente uma documentação a que falte um item obrigatório.
- **Seed inicial:**

  | Tipo de demanda | Obrigatórios | Opcional |
  |---|---|---|
  | `SUPPLIER_HIRING` | `SUPPLIER_CNPJ_CARD`, `SUPPLIER_QUALIFICATION_DOCUMENTS` | `COMMERCIAL_PROPOSAL` |
  | `CONTRACT_SIGNING` | `CONTRACT_DRAFT`, `FINANCIAL_OPINION` | `SIGNATORY_POWERS` |
  | `SETTLEMENT_PAYMENT` | `SETTLEMENT_AGREEMENT`, `PAYMENT_INSTRUCTIONS` | `COURT_APPROVAL` |
  | `LAWSUIT_CLOSURE` | `CLOSURE_PETITION`, `CASE_PROGRESS_REPORT` | `FINAL_JUDGMENT` |
  | `PROPOSAL_ACCEPTANCE` | `PROPOSAL_DOCUMENT`, `FINANCIAL_OPINION` | `COUNTERPARTY_REGISTRATION` |

- **Manutenção.** As regras são mantidas pela API administrativa (`/api/v1/admin/checklist-rules`, papel `ADMIN`).
  - O tipo de demanda de uma regra não muda.
  - Não há duas regras iguais no mesmo tipo.
  - Uma regra já usada por alguma demanda não pode ser excluída nem trocar o documento exigido; para deixar de exigi-lo, a regra é marcada como não obrigatória.
  - Descrição e obrigatoriedade podem mudar a qualquer momento.

---

## 10. Estratégia de IA (anti-alucinação)

Fluxo em cadeia (prompt chaining), nunca uma única chamada monolítica:

1. **Extração estruturada** (`AiExtractedFact`) — o modelo extrai fatos do documento em JSON. Sem opinião jurídica nesta etapa. Detalhes na seção 14 (Prompt 11): schema por tipo de demanda, prompt versionado, dupla validação, uma única nova tentativa e alerta quando ela também falha.
2. **Recuperação normativa (RAG)** — para cada `question_key`, recuperar os `KnowledgeBaseChunk` mais relevantes via similaridade de embedding.
3. **Resposta estruturada** — o modelo responde **apenas com base nos trechos recuperados**, no formato:
   ```json
   {
     "question_key": "CAN_SIGN_CONTRACT",
     "answer": "string",
     "confidence_score": 0.0,
     "cited_chunks": ["chunk_id_1", "chunk_id_2"],
     "alerts": ["string"]
   }
   ```
   Validado contra um JSON Schema fixo antes de ser persistido.
4. **Segunda checagem (self-verification)** — para perguntas críticas (`CAN_SIGN_CONTRACT`, `CAN_PAY_SETTLEMENT`, `CAN_CLOSE_LAWSUIT`), uma chamada adicional confere se a resposta é de fato suportada pelos `cited_chunks`.
5. Toda chamada ao LLM grava qual `PromptVersion` foi usada.
6. Nenhuma resposta é aceita sem `cited_chunks` preenchido, exceto quando o `answer` for explicitamente "informação não encontrada na base normativa".

O humano sempre vê: pergunta, resposta, confiança e o texto dos trechos citados — nunca só o resultado final.

---

## 11. Requisitos não funcionais

**Escalabilidade**
- Ingestão desacoplada de processamento via fila; workers escaláveis horizontalmente.
- Sem estado em memória entre requisições (qualquer worker pode processar qualquer evento).
- Paginação obrigatória em qualquer endpoint de listagem.

**Resiliência**
- Toda chamada externa (LLM, storage, fila) protegida por circuit breaker + retry com backoff exponencial (Resilience4j).
- Timeouts explícitos em todas as integrações externas.
- Falha em uma demanda não pode travar o processamento de outras (isolamento por bulkhead/thread pool dedicado).

**Idempotência**
- Toda mensagem consumida da fila carrega uma `idempotency_key`.
- Antes de processar, verificar existência da chave em `processing_events`; se já processada, ignorar (log, não erro).
- Endpoints de escrita que podem ser re-chamados (ex.: registrar decisão) devem aceitar uma chave de idempotência do cliente.
- Na ingestão, a chave vem no cabeçalho `Idempotency-Key` e é gravada em `processing_events` com o prefixo `legal-case-ingestion:`, para nunca colidir com a chave de uma mensagem de fila. Um reenvio com a mesma chave devolve `201` com a demanda original.
- A chave de um evento de fila é derivada do próprio evento (`LEGAL_CASE_RECEIVED:{eventId}`), e não da chave enviada pelo cliente.
- Estados de um evento em `processing_events`: `IN_PROGRESS`, `PROCESSED` ou `FAILED`. Um evento `FAILED` pode ser reservado de novo, e um `IN_PROGRESS` em outra réplica é descartado.
- A marca de falha é gravada fora da transação do processamento, porque um rollback apagaria o próprio registro da falha.

**Etapas retomáveis**
- Cada etapa do consumidor confere o status atual antes de agir. Uma nova tentativa não repete a classificação de uma demanda que já está em `EXTRACTING`.
- A extração de texto grava cada documento assim que ele termina, e uma nova tentativa só lê os documentos que ainda não têm texto (índice único em `document_text_contents.document_id`).
- O OCR roda fora de qualquer transação, para não prender uma conexão do banco durante minutos.

**Falha de documento × falha de ambiente**
- Um arquivo ilegível (corrompido ou protegido por senha) é problema do documento. Ele é registrado como `FAILED`, e a demanda segue.
- Tesseract ausente, OCR com tempo esgotado e storage indisponível são problemas de ambiente. A exceção sobe e a mensagem volta para a fila: o retry com backoff vai de 1s a 10s, com 4 tentativas, e depois a mensagem segue para a dead-letter.
- A ausência do Tesseract nunca vira "documento sem texto" em silêncio.

---

## 12. Segurança e LGPD

- Dados de demandas jurídicas são sensíveis — controle de acesso por papel (ex.: `ANALYST`, `LEGAL_REVIEWER`, `ADMIN`).
  - **Estado atual (Prompt 09):** só `/api/v1/admin/**` é protegido, por HTTP Basic, com o papel `ADMIN`, sem sessão e sem CSRF.
  - As credenciais vêm de `LEXFLOW_ADMIN_USERNAME` e `LEXFLOW_ADMIN_PASSWORD`; a senha pode ser informada em texto, e aí vira hash na inicialização, ou já como `{bcrypt}`. Em produção, as duas variáveis são obrigatórias.
  - As demais rotas seguem abertas até a escolha do provedor de identidade.
- Nunca logar conteúdo integral de documentos, apenas metadados e hashes.
  - O `toString` de `DocumentTextContent` e de `ExtractedText` mostra só o tamanho do texto.
  - O motivo da classificação gravado no histórico traz apenas tipos e palavras-chave da tabela, nunca trechos do texto do requisitante.
- Eventos de fila carregam apenas identificadores e metadados, nunca o conteúdo de documentos.
- O nome de arquivo enviado pelo cliente é dado não confiável e não compõe o caminho no storage.
- Avaliar política de retenção de dados do provedor de LLM antes de enviar documentos com dados pessoais.
- `AuditLog` é append-only — nenhuma linha pode ser alterada ou apagada por código de aplicação.

---

## 13. Convenções de código

- Comentários de código e Javadoc: **português**.
- Nomes de classes, métodos, variáveis, tabelas, enums, tópicos: **inglês**.
- Toda exceção de domínio deve estender uma `DomainException` base e ter nome descritivo (`InvalidStatusTransitionException`, `ChecklistRuleNotFoundException`).
- Toda entidade de domínio é imutável sempre que possível (usar métodos que retornam novo estado em vez de setters, quando fizer sentido).
- Cobertura de teste mínima esperada para `domain` e `application`: 80%.

---

## 14. Decisões de implementação registradas (Prompts 01 a 11)

Esta seção consolida as decisões tomadas durante a implementação que não constavam das seções anteriores. Qualquer mudança deve ser registrada aqui antes de ser implementada.

**Estrutura e build (Prompt 01)**
- Gradle 9 com Kotlin DSL e wrapper versionado. As versões de dependências ficam centralizadas em `gradle/libs.versions.toml`.
- A classe `@SpringBootApplication` (`LexFlowApplication`) fica em `lexflow-api`, no pacote `com.lexflow`.
- As classes de `lexflow-application` não têm anotações do Spring e são montadas como beans em `LegalCaseUseCaseConfiguration`, no módulo da API.
- Perfis `dev` (padrão) e `prod`. Em `prod`, as credenciais vêm apenas de variáveis de ambiente.

**Domínio e persistência (Prompts 02 e 03)**
- As entidades de domínio são `record` imutáveis, sem anotações de persistência. As entidades JPA e os mappers ficam em `lexflow-infrastructure`.
- Nos testes, o Hibernate roda com `ddl-auto: validate`: se entidade e migration divergirem, o contexto não sobe.
- `AiAnalysisResponse` recusa, já no construtor, uma resposta sem `cited_chunks`, salvo quando o texto declara "informação não encontrada na base normativa".

**Transações (Prompt 04)**
- A porta `TransactionRunner` permite que os casos de uso delimitem transações sem depender do Spring.

**Ingestão (Prompt 05)**
- `POST /api/v1/legal-cases` (multipart) recebe `caseType`, `requester`, `priority`, `externalReference` e `description`, os três últimos opcionais, além de um ou mais arquivos em `files` e, opcionalmente, `documentTypes` com um tipo por arquivo, na mesma ordem (Prompt 09). Responde `201` com `id`, `statusUrl` e o cabeçalho `Location`.
- `GET /api/v1/legal-cases/{id}` devolve o status, os metadados e os arquivos da demanda, sem expor o caminho no storage.
- A ingestão não classifica, não extrai e não chama IA. Ela grava tudo em uma transação e só depois publica o evento.
- Os erros seguem um formato único (`code`, `message`, `timestamp`, `path`):

  | Situação | HTTP |
  |---|---|
  | `DomainException` e entrada inválida (inclusive JSON malformado) | `400` |
  | credenciais ausentes ou inválidas | `401` |
  | usuário sem o papel exigido | `403` |
  | demanda ou regra de checklist inexistente | `404` |
  | tamanho de upload excedido | `413` |
  | conflito de idempotência, de transição, regra duplicada ou regra em uso | `409` |
  | falha do storage | `503` |
  | demais erros (mensagem genérica) | `500` |

- Limites de upload: 25 MB por arquivo e 100 MB por requisição, configuráveis.

**Storage (Prompt 06)**
- A chave do objeto é derivada do conteúdo: `legal-cases/{legalCaseId}/{checksum}{extensão}`. Isso torna o upload idempotente por construção e agrupa os objetos por demanda, o que sustenta o expurgo por caso.
- O checksum SHA-256 é calculado pela aplicação, não pelo adapter.
- Tempos limite explícitos no cliente S3: 30 s por chamada e 10 s por tentativa.

**Fila (Prompt 07)**
- Topologia:
  - exchange `lexflow.events` (topic), routing key `legal-case.received` e fila `lexflow.legal-case-received`;
  - dead-letter em `lexflow.events.dlx` e na fila `lexflow.legal-case-received.dlq`.
- Por que RabbitMQ: para milhares de eventos por dia, Kafka e RabbitMQ atendem, e o desempate foi a simplicidade operacional. No RabbitMQ, a dead-letter é um argumento de fila e o paralelismo vem de mais réplicas; não há necessidade de reprocessar o histórico. Se surgir a exigência de replay de eventos antigos, a troca fica contida em `RabbitMqConfiguration` e nos dois adapters de mensageria.
- O consumidor processa uma mensagem por vez por thread (`prefetch: 1`). Mensagens rejeitadas não voltam para a fila principal: vão para a dead-letter.
- Limitação conhecida: não há bloqueio otimista em `legal_cases`, e dois eventos *diferentes* para a mesma demanda poderiam concorrer. Hoje cada demanda publica um único evento; o tratamento fica para o Prompt 17.

**Classificação e extração (Prompt 08)**
- As regras de classificação estão na seção 5.1, e o modelo de dados, na seção 6.
- Estratégia de extração por formato:

  | Formato | Estratégia | Método registrado |
  |---|---|---|
  | PDF com texto | lê a camada de texto | `NATIVE_TEXT` |
  | PDF digitalizado | renderiza as páginas a 300 dpi e aplica OCR | `OCR` |
  | DOCX | lê a camada de texto; imagens embutidas não passam por OCR | `NATIVE_TEXT` |
  | JPEG e PNG | OCR | `OCR` |

- Um PDF é considerado digitalizado quando tem menos de 10 letras ou dígitos por página, em média.
- O texto é saneado antes de ser gravado: remove-se o caractere nulo, que o PostgreSQL recusa, e as bordas em branco.
- Configuração em `lexflow.extraction.*`: `ocr-language`, `tesseract-path`, `ocr-timeout` (padrão 2 min por imagem ou página), `ocr-dpi` e `min-native-characters-per-page`. Um `tesseract-path` inexistente impede a aplicação de subir. Um Tesseract ausente só gera um aviso na inicialização e faz falhar os documentos que precisam de OCR.
- A demanda termina esta etapa em `EXTRACTING`, e o texto gravado é a entrada do Prompt 11.

**Checklist documental (Prompt 09)**
- As regras de negócio estão na seção 9, e o modelo de dados, na seção 6.
- O CRUD administrativo usa REST com Spring Security (HTTP Basic), e não apenas o seed: assim a área jurídica ajusta as regras sem deploy.
- Endpoints novos:
  - `GET /api/v1/legal-cases/{id}/checklist`: itens, documentos obrigatórios faltantes, `evaluated` e `hasSufficientDocumentation`;
  - `GET/POST /api/v1/admin/checklist-rules` e `GET/PUT/DELETE /api/v1/admin/checklist-rules/{id}`.
- Listagens são paginadas com `page` (a partir de 0) e `size` (padrão 20, máximo 100). A resposta traz `content`, `page`, `size`, `totalElements` e `totalPages`.
- Os documentos de uma demanda são lidos em ordem estável (data de envio, nome, identificador), porque a ordem decide qual documento satisfaz um item.

**Cliente LLM (Prompt 10)**
- **Porta.** `LlmClientPort` fica em `lexflow-application.llm` e é genérica: `complete(LlmRequest): LlmResponse`, sem nenhuma regra jurídica.
  - `LlmRequest` traz o prompt de sistema, o prompt, o JSON Schema opcional, o modelo, `maxTokens`, `temperature` e `effort`.
  - `LlmResponse` traz o modelo pedido e o que respondeu, o texto, o JSON validado, o `stop_reason`, o uso de tokens, o `request-id` e a latência.
- **Adapter.** `AnthropicMessagesClient`, em `lexflow-infrastructure.llm`, chama a API por `WebClient`, sem o SDK: o Prompt 10 pede `WebClient` e testes com WireMock, e as proteções ficam todas no Resilience4j, sem somar novas tentativas do SDK às do Resilience4j.
- **Requisição.** Cabeçalhos `x-api-key` e `anthropic-version: 2023-06-01`. A resposta estruturada é pedida em `output_config.format` (`type: json_schema`) e o esforço em `output_config.effort`.
- **Modelo e parâmetros.** O modelo padrão é `claude-opus-5`, com `max_tokens` padrão de 16000 (as chamadas não usam streaming). O Claude Opus 5 recusa `temperature`; o ajuste de qualidade é o `effort`.
- **Recusa por política.** Ligada por padrão: `fallbacks: "default"` com o beta `server-side-fallback-2026-07-01`, para o provedor tentar outro modelo na mesma chamada. O modelo que respondeu é sempre o registrado como `model_version`.
- **Validação.** A resposta estruturada é validada de novo no cliente (JSON Schema 2020-12, `networknt/json-schema-validator` 1.5, com Jackson 2). Resposta que não é JSON, viola o schema ou foi truncada (`stop_reason: max_tokens`) vira `LlmResponseValidationException`, sem nova tentativa: a decisão de tentar de novo com prompt reforçado é do caso de uso (Prompt 11). `stop_reason: refusal` vira `LlmRefusalException`.
- **Proteções**, na instância `llm`, de dentro para fora:

  | Proteção | Padrão |
  |---|---|
  | Bulkhead | 16 chamadas simultâneas por réplica, espera de 10 s |
  | Time limiter | 180 s por tentativa (o timeout HTTP é de 190 s) |
  | Circuit breaker | janela de 20 chamadas; abre com 50% de falhas ou 80% de chamadas acima de 120 s; fica 30 s aberto |
  | Retry | 3 tentativas, com backoff exponencial de 2 s a 20 s |

- **Classificação das falhas.**
  - Repetidas e contadas pelo circuito: 408, 409, 429, 5xx, 529, timeout e falha de conexão (`LlmUnavailableException`).
  - Não repetidas e não contadas: os demais 4xx (`LlmRequestRejectedException`), a validação e a recusa.
  - Circuito aberto e bulkhead cheio também viram `LlmUnavailableException`.
- **Log.** O prompt e a resposta nunca vão para o log. Em `INFO` saem modelo, tokens, latência e `request-id`; em `DEBUG`, só o tamanho e o hash do prompt.
- **Chave de API.** Vem de `ANTHROPIC_API_KEY`. Sem ela, a aplicação sobe e só as chamadas falham.

**Extração de fatos (Prompt 11)**
- `ExtractLegalFactsUseCase` roda no consumidor da fila, logo depois da extração de texto, e só para documentos com texto (`EXTRACTED`). Pode ser desligado por `lexflow.pipeline.fact-extraction.enabled`; desligado, a demanda para em `EXTRACTING`.
- **Schema.** `FactExtractionSchema` define uma parte comum e uma parte específica por tipo de demanda.
  - Parte comum: `documentKind`, `parties`, `monetaryValues`, `relevantDates` e `keyClauses`.
  - Parte específica (`specificFacts`): quatro campos por tipo de demanda.
  - Todos os campos são obrigatórios e anuláveis, com `additionalProperties: false`. Mudar o schema exige nova versão do prompt.
- **Prompt.** O texto vem de `prompt_versions` (`FACT_EXTRACTION`), com as seções `SISTEMA`, `USUARIO` e `REFORCO` e marcadores `{{...}}` preenchidos em uma única passada: o texto do documento nunca é reinterpretado como template.
  - O documento vai entre `<documento>` e `</documento>`, e o modelo é instruído a ignorar instruções contidas nele.
  - Cada fato grava `model_version` (o modelo que respondeu) e `prompt_version_id`.
- **Validação.** O caso de uso valida o JSON contra o schema (porta `StructuredOutputValidator`) além da validação do cliente LLM. Em caso de falha, há uma nova tentativa com o bloco `REFORCO` (formato exigido e violações encontradas); se falhar de novo, nada é gravado e o documento recebe o alerta `FACT_EXTRACTION_INVALID_OUTPUT`.
- **Outros alertas.** Recusa do modelo gera `FACT_EXTRACTION_REFUSED`. Texto acima de `max-document-characters` (padrão 400000) gera `DOCUMENT_TOO_LONG_FOR_EXTRACTION`, sem chamada: o texto nunca é truncado.
- **Falhas de ambiente.** LLM indisponível, pedido recusado pelo provedor e prompt sem versão ativa sobem como exceção: a mensagem volta para a fila.
- **Alertas e avanço.** Os alertas ficam em `legal_case_alerts`, separados da auditoria, porque participam do fluxo. Com algum alerta em aberto, a demanda fica em `EXTRACTING`; sem nenhum, avança para `AI_ANALYSIS_IN_PROGRESS`. Os alertas aparecem no `GET /api/v1/legal-cases/{id}`; resolvê-los é tarefa da revisão humana (Prompt 15).
- **Retomada.** Documentos com fatos ou com alerta não voltam ao LLM. Uma demanda já em `AI_ANALYSIS_IN_PROGRESS` faz a etapa ser considerada concluída.
- **Testes.** Nenhum teste automatizado chama o LLM real. O gabarito do contrato de teste fica em `fixtures/facts/contrato-texto-nativo.gabarito.json`.
