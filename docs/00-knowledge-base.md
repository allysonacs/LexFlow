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
| Vetor de um texto | `Embedding` | Representação vetorial de um texto, usada para medir proximidade de assunto |
| Trecho recuperado | `RetrievedChunk` | Trecho devolvido pela busca por similaridade, com a fonte e o grau de proximidade |
| Resposta da IA | `AiAnalysisResponse` | Resposta estruturada a uma pergunta jurídica específica, com fonte citada e confiança |
| Origem da resposta | `AnswerSource` | Se a resposta veio do modelo (`LLM`) ou de uma regra de código (`DETERMINISTIC`) |
| Resultado da segunda checagem | `VerificationStatus` | `NOT_VERIFIED`, `VERIFIED` ou `FAILED` para uma resposta crítica |
| Versão de prompt | `PromptVersion` | Registro versionado do template de prompt usado, para rastreabilidade |
| Decisão humana | `Decision` | Decisão final do responsável: aprovar, reprovar ou devolver |
| Texto extraído | `DocumentTextContent` | Texto de um documento, obtido da camada de texto ou por OCR, sem nenhum uso de LLM (Prompt 08) |
| Classificação da demanda | `LegalCaseClassification` | Resultado da validação do tipo de demanda por palavras-chave (seção 5.1) |
| Alerta da demanda | `LegalCaseAlert` | Situação que o pipeline não resolve sozinho (ex.: fatos inválidos após nova tentativa); segura a demanda até um humano tratar |
| Log de auditoria | `AuditLog` | Registro imutável de qualquer ação relevante no sistema |
| Ação auditada | `AuditAction` | O que foi registrado: criação, transição, resposta da IA, verificação ou decisão |
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
| `AI_ANALYSIS_IN_PROGRESS → PENDING_HUMAN_REVIEW` | cadeia de prompts (Prompt 13), quando todas as perguntas aplicáveis foram respondidas e nenhum alerta está em aberto |
| `PENDING_HUMAN_REVIEW → APPROVED \| REJECTED \| RETURNED_FOR_CORRECTION` | registro da decisão humana (`POST /api/v1/legal-cases/{id}/decisions`, Prompt 15) |
| `RETURNED_FOR_CORRECTION → RECEIVED` | reenvio de documentação (`POST /api/v1/legal-cases/{id}/documents`, Prompt 15) |

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
  cited_chunks, answer_source, model_version, prompt_version_id,
  verification_status, verification_notes, created_at
)
-- answer_source: LLM | DETERMINISTIC
-- verification_status: NOT_VERIFIED | VERIFIED | FAILED (Prompt 14)
-- verification_notes: justificativa da segunda checagem; nula enquanto ela não aconteceu
-- único por (legal_case_id, question_key): uma resposta por pergunta em cada demanda
-- LLM exige model_version e prompt_version_id; DETERMINISTIC exige os dois nulos (restrição de banco)

prompt_versions (
  id, prompt_key, version, template_text, active, created_at
)
-- no máximo uma versão ativa por prompt_key; uma versão nunca é editada, só substituída por outra

legal_case_alerts (
  id, legal_case_id, document_id, alert_type, message, created_at, resolved_at
)
-- alert_type: FACT_EXTRACTION_INVALID_OUTPUT | FACT_EXTRACTION_REFUSED | DOCUMENT_TOO_LONG_FOR_EXTRACTION
--   | AI_ANALYSIS_INVALID_OUTPUT | AI_ANALYSIS_REFUSED
-- alerta aberto (resolved_at nulo) impede a demanda de avançar automaticamente

decisions (
  id, legal_case_id, decision_type, decided_by, decided_at, comments
)
-- decision_type: APPROVED | REJECTED | RETURNED_FOR_CORRECTION

audit_logs (
  id, entity_type, entity_id, legal_case_id, action, actor, payload, occurred_at
)
-- entity_type: LEGAL_CASE | AI_ANALYSIS_RESPONSE | DECISION
-- action: LEGAL_CASE_RECEIVED | STATUS_CHANGED | AI_ANSWER_RECORDED | AI_ANSWER_VERIFIED | DECISION_REGISTERED
-- actor: usuário identificado, SYSTEM ou AI
-- legal_case_id: correlação, para a linha do tempo de um caso não depender de conhecer, de fora,
--   todos os identificadores que pertencem a ele
-- append-only: gatilho de banco recusa UPDATE, DELETE e TRUNCATE (Prompt 16)

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
| `V7__legal_analysis.sql` | `ai_analysis_responses.answer_source`, restrição de rastreabilidade, índice único por pergunta e o prompt `LEGAL_ANALYSIS` v1 (Prompt 13) |
| `V8__answer_verification.sql` | `ai_analysis_responses.verification_status` e `verification_notes`, e o prompt `ANSWER_VERIFICATION` v1 (Prompt 14) |
| `V9__audit_log_append_only.sql` | `audit_logs.legal_case_id` e o gatilho que torna a trilha append-only (Prompt 16) |
| `V10__ai_human_agreement.sql` | visão `ai_human_agreement`, base da métrica de concordância entre a IA e o revisor (Prompt 18) |

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
- **Embeddings (RAG):** provedor separado do LLM, também por `WebClient`, no formato `POST /v1/embeddings` (`input`, `model`, `input_type`, `output_dimension`). O padrão é a Voyage AI, com o modelo `voyage-3-large` em 1536 dimensões. A justificativa da separação está na seção 14 (Prompt 12).
- **Resiliência:** Resilience4j (circuit breaker, retry, timeout, bulkhead), configurado em `resilience4j.*.instances.<nome>` no `application.yml`
- **Observabilidade:** Micrometer (métricas, expostas em `/actuator/prometheus`) e Micrometer Tracing com OpenTelemetry (exportação OTLP). Log estruturado em JSON (ECS) apenas no perfil `prod`
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
2. **Recuperação normativa (RAG)** — para cada `question_key`, recuperar os `KnowledgeBaseChunk` mais relevantes via similaridade de embedding. Detalhes na seção 14 (Prompt 12): divisão determinística em trechos, embeddings por provedor dedicado, busca por distância de cosseno no pgvector e corte por similaridade mínima.
3. **Resposta estruturada** — uma chamada por `question_key`, nunca uma chamada monolítica: perguntas diferentes se apoiam em normas diferentes, e uma resposta errada não contamina as outras. O modelo responde **apenas com base nos trechos recuperados**, no formato:
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
4. **Segunda checagem (self-verification)** — para perguntas críticas (`CAN_SIGN_CONTRACT`, `CAN_PAY_SETTLEMENT`, `CAN_CLOSE_LAWSUIT`, ampliáveis por configuração), uma chamada adicional confere se a resposta é de fato suportada pelos `cited_chunks`. Ela roda antes de a demanda chegar ao revisor e nunca reescreve a resposta. Detalhes na seção 14 (Prompt 14).
5. Toda chamada ao LLM grava qual `PromptVersion` foi usada.
6. Nenhuma resposta do modelo é aceita sem `cited_chunks` preenchido, exceto quando o `answer` for explicitamente "informação não encontrada na base normativa".
7. Nenhuma resposta é aceita citando um trecho que não foi fornecido naquela chamada: um `chunk_id` inventado é alucinação com aparência de fundamento.
8. Duas perguntas são resolvidas por código, sem chamar o modelo, e gravadas com `answer_source = DETERMINISTIC` (Prompt 13).

O humano sempre vê: pergunta, resposta, confiança e o texto dos trechos citados — nunca só o resultado final.

---

## 11. Requisitos não funcionais

**Escalabilidade**
- Ingestão desacoplada de processamento via fila; workers escaláveis horizontalmente.
- Sem estado em memória entre requisições (qualquer worker pode processar qualquer evento).
- Paginação obrigatória em qualquer endpoint de listagem.

**Resiliência**
- Toda chamada externa (LLM, embeddings, storage, fila, banco) protegida por tempo limite explícito, retry com backoff exponencial e circuit breaker onde aplicável (Resilience4j). Instâncias: `llm`, `embeddings` e `storage`.
- Quem repete é sempre o Resilience4j: as tentativas próprias das bibliotecas clientes (SDK da AWS) foram desligadas, porque duas camadas de retry multiplicam as chamadas e tornam imprevisível o tempo total de uma falha.
- Falha em uma demanda não pode travar o processamento de outras (isolamento por bulkhead/thread pool dedicado).
- A janela de novas tentativas do consumidor precisa ser maior do que uma indisponibilidade típica de um provedor externo: 6 tentativas, com espera de 2 s a 60 s, atravessam mais de dois minutos de queda sem mandar a demanda para a dead-letter (Prompt 17).
- O detalhe operacional de cada ponto de falha está em `docs/resilience-runbook.md`.

**Idempotência**
- Toda mensagem consumida da fila carrega uma `idempotency_key`.
- Antes de processar, verificar existência da chave em `processing_events`; se já processada, ignorar (log, não erro).
- Endpoints de escrita que podem ser re-chamados (ex.: registrar decisão) devem aceitar uma chave de idempotência do cliente.
- A chave do cliente vem no cabeçalho `Idempotency-Key` e é gravada em `processing_events` com o prefixo do seu escopo (`IdempotencyNamespace`): `legal-case-ingestion:` na ingestão e `legal-case-decision:` na decisão humana. O prefixo é o que impede que a mesma chave, usada em dois endpoints ou por uma mensagem de fila, seja confundida com uma repetição. Um reenvio com a mesma chave devolve `201` com o resultado original.
- A chave de um evento de fila é derivada do próprio evento (`LEGAL_CASE_RECEIVED:{eventId}`), e não da chave enviada pelo cliente.
- Estados de um evento em `processing_events`: `IN_PROGRESS`, `PROCESSED` ou `FAILED`. Um evento `FAILED` pode ser reservado de novo, e um `IN_PROGRESS` em outra réplica é descartado.
- As chaves já processadas expiram depois de `lexflow.idempotency.retention` (padrão 30 dias), removidas por um job horário em lotes. A retenção precisa ser maior do que a janela de novas tentativas de qualquer cliente: apagada a chave, um reenvio muito atrasado voltaria a criar a demanda. Eventos `FAILED` e `IN_PROGRESS` nunca são removidos.
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
  - **Estado atual (Prompts 09 e 12):** `/api/v1/admin/**` e `/api/v1/knowledge-base/**` são protegidos, por HTTP Basic, com o papel `ADMIN`, sem sessão e sem CSRF. A base normativa entra nessa lista porque indexar uma norma muda o fundamento de toda resposta futura da IA.
  - As credenciais vêm de `LEXFLOW_ADMIN_USERNAME` e `LEXFLOW_ADMIN_PASSWORD`; a senha pode ser informada em texto, e aí vira hash na inicialização, ou já como `{bcrypt}`. Em produção, as duas variáveis são obrigatórias.
  - As demais rotas seguem abertas até a escolha do provedor de identidade.
- Nunca logar conteúdo integral de documentos, apenas metadados e hashes.
  - O `toString` de `DocumentTextContent` e de `ExtractedText` mostra só o tamanho do texto.
  - O motivo da classificação gravado no histórico traz apenas tipos e palavras-chave da tabela, nunca trechos do texto do requisitante.
- Eventos de fila carregam apenas identificadores e metadados, nunca o conteúdo de documentos.
- O nome de arquivo enviado pelo cliente é dado não confiável e não compõe o caminho no storage.
- Avaliar política de retenção de dados do provedor de LLM antes de enviar documentos com dados pessoais.
- `AuditLog` é append-only — e a garantia é do banco: um gatilho recusa `UPDATE`, `DELETE` e `TRUNCATE` (Prompt 16). Uma regra que só existe no código Java protege apenas o caminho que passa por Java.
- O `payload` da auditoria guarda metadados do que mudou — status, tipo de decisão, identificadores —, nunca conteúdo de documento, texto de resposta ou comentário de decisão.

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

**Base normativa e RAG (Prompt 12)**
- **Só recupera texto.** Nada neste componente decide questão jurídica: ele divide normas em trechos, indexa e devolve os mais próximos de uma consulta. Quem responde é a cadeia de prompts (Prompt 13), e quem decide é o revisor humano.
- **Provedor de embeddings separado do LLM.** A Anthropic não expõe endpoint de embeddings, e o modelo de vetores tem ciclo de vida próprio: trocá-lo obriga a reindexar toda a base. Por isso a porta `EmbeddingClientPort` é distinta da `LlmClientPort`, com o adapter `VoyageEmbeddingClient` em `lexflow-infrastructure.embedding`.
  - O pedido informa `input_type`: `document` para um trecho indexado e `query` para uma pergunta. Os modelos atuais projetam os dois em regiões próximas do espaço vetorial, e é isso que faz uma pergunta curta encontrar um parágrafo longo de norma.
  - A dimensão pedida ao provedor precisa ser igual à da coluna `vector` (1536). Um vetor de outra dimensão é recusado como pedido inválido, sem nova tentativa: é erro de configuração, não falha transitória.
  - Proteções na instância `embeddings` do Resilience4j, no mesmo desenho do cliente LLM: bulkhead de 8 chamadas, 45 s por tentativa, circuito de janela 20 e retry de 3 tentativas com backoff de 1 s a 10 s.
  - A chave vem de `LEXFLOW_EMBEDDINGS_API_KEY` (ou `VOYAGE_API_KEY`). Sem ela, a aplicação sobe e só a indexação e a recuperação falham.
- **Divisão em trechos.** `TextChunker` fica no domínio porque é regra pura e precisa ser determinística: reindexar a mesma norma tem de produzir exatamente os mesmos trechos.
  - A divisão vai do maior para o menor: parágrafos, depois frases e, por último, corte no limite — nesta ordem, para respeitar como uma norma se organiza.
  - O padrão é 1200 caracteres por trecho, com 200 de sobreposição, para que uma regra na fronteira de dois trechos continue legível em pelo menos um deles.
  - Mudar a política só afeta o que for indexado a partir dali; as fontes já indexadas mantêm a divisão antiga até serem reindexadas.
- **Indexação.** `IngestKnowledgeBaseSourceService` divide, vetoriza e grava fonte e trechos na mesma transação; uma fonte sem trechos seria uma norma que existe no catálogo e nunca é recuperada. As chamadas ao provedor ficam fora da transação, pelo mesmo motivo do OCR (seção 11).
  - Reindexar substitui: os trechos antigos são apagados antes de os novos entrarem, para a mesma norma não ser recuperada em duplicata.
  - A fonte pode chegar como texto ou como arquivo; arquivos `.txt` e `.md` são lidos direto, e os demais formatos passam pelo mesmo extrator dos documentos das demandas, OCR incluído.
- **Recuperação.** `KnowledgeBaseRetriever` vetoriza a consulta e pede ao banco os N trechos mais próximos. A busca é uma consulta nativa com o operador `<=>` do pgvector, que usa o índice HNSW criado na V1; JPQL não conhece esse operador. O vetor vai como texto e é convertido com `CAST(... AS vector)`, porque `::vector` seria lido como parâmetro pelo Hibernate.
  - Cada trecho volta com o título e o tipo da fonte, e não só com o identificador: é assim que ele será citado ao revisor humano.
  - Trechos abaixo de `min-similarity` (padrão 0,2) são descartados. A busca vetorial sempre devolve os N mais próximos, mesmo quando nenhum trata do assunto, e norma irrelevante no contexto é convite à alucinação: é preferível o modelo responder "informação não encontrada na base normativa".
- **Endpoints novos**, todos com papel `ADMIN`:
  - `POST /api/v1/knowledge-base/sources` (JSON ou multipart), `GET` da listagem paginada e `GET /{id}` com os trechos;
  - `GET /api/v1/knowledge-base/search`, que mostra o que a busca devolveria para uma consulta, sem chamar o LLM — é ferramenta de conferência da base.
- **Sem migration nova.** As tabelas `knowledge_base_sources` e `knowledge_base_chunks`, a extensão `vector` e o índice HNSW já vieram na `V1`.
- **Testes.** Nenhum teste automatizado chama o provedor real de embeddings. Nos testes, o modelo é o `LexicalEmbeddingClient`: determinístico e lexical, ele reproduz a única propriedade de que a recuperação depende — textos sobre o mesmo assunto ficam próximos.

**Cadeia de prompts (Prompt 13)**
- `AnalyzeLegalCaseUseCase` roda no consumidor da fila, logo depois da extração de fatos, e leva a demanda de `AI_ANALYSIS_IN_PROGRESS` a `PENDING_HUMAN_REVIEW`. Pode ser desligado por `lexflow.pipeline.legal-analysis.enabled`; desligado, a demanda para em `AI_ANALYSIS_IN_PROGRESS`.
- **Uma pergunta de cada vez.** Para cada `question_key` aplicável ao tipo há uma recuperação normativa própria e uma chamada própria ao modelo. O prompt leva os fatos extraídos, a situação do checklist, os trechos recuperados com os seus identificadores e a lista de identificadores que podem ser citados.
- **O que não vai ao modelo.** Duas situações são resolvidas por código e gravadas como `DETERMINISTIC`, sem modelo e sem versão de prompt:
  - `HAS_SUFFICIENT_DOCUMENTATION` quando o checklist determinístico aponta documento obrigatório faltante — perguntar ao modelo o que o código já sabe só criaria a chance de ele discordar. Com a documentação completa, a pergunta vai ao modelo, para o caso de um documento presente mas insuficiente (seção 5);
  - qualquer pergunta para a qual a recuperação não trouxe nenhum trecho: sem contexto não há em que se apoiar, e a resposta honesta é declarar que a informação não está na base.
- **Barreiras antes de gravar.** Além do JSON Schema fixo, o caso de uso confere que o `question_key` respondido é o pedido e que todo trecho citado estava entre os fornecidos. Falhando, há **uma** nova tentativa com o bloco `REFORCO`, que lista as violações; falhando de novo, nada é gravado para aquela pergunta e a demanda recebe `AI_ANALYSIS_INVALID_OUTPUT`. Recusa do modelo gera `AI_ANALYSIS_REFUSED`.
- **Alertas do modelo.** O campo `alerts` da resposta é anexado ao texto gravado, sob "Pontos para verificação:", em vez de ir para uma tabela à parte: são parte do que a pessoa precisa ler junto com a resposta.
- **Consulta da recuperação.** É a pergunta seguida do tipo da demanda e dos fatos, limitada a 800 caracteres: despejar todos os fatos diluiria a pergunta e faria a busca responder "que norma se parece com este contrato?" em vez de "que norma trata desta pergunta?".
- **Retomada.** Perguntas já respondidas não voltam ao modelo (índice único por `(legal_case_id, question_key)`). Com um alerta desta etapa em aberto, nenhuma chamada nova é feita: a demanda espera tratamento humano, e repetir custaria dinheiro sem mudar o desfecho.
- **Leitura do JSON.** A camada de aplicação não conhece biblioteca de serialização (seção 7): a porta `LegalAnalysisAnswerReader` é implementada com Jackson na infraestrutura.
- **Testes.** Nenhum teste chama o LLM real. O critério de aceite roda em `LegalCaseAnalysisIT`, de ponta a ponta, com uma norma indexada pela API e o LLM simulado.

**Segunda checagem (Prompt 14)**
- `VerifyAiAnalysisResponseUseCase` implementa a porta `AiAnalysisVerifier`, que a cadeia de prompts chama **antes** da transição para `PENDING_HUMAN_REVIEW`: uma resposta reprovada precisa chegar ao revisor já sinalizada, e não sinalizada depois.
- **Ela confere, não reescreve.** O texto da resposta nunca muda. Muda o selo: `VERIFIED`, ou `FAILED` com a confiança zerada, mais a justificativa em `verification_notes`.
- **O que é verificado.** Só respostas de perguntas críticas que vieram do modelo e citaram algum trecho. Ficam de fora as determinísticas, as que declaram que a informação não está na base e as perguntas não críticas — cada verificação é uma chamada a mais.
- **Se a própria checagem falhar.** Saída fora do formato ou recusa deixam a resposta em `NOT_VERIFIED`: a checagem é uma camada extra e não pode impedir a demanda de chegar ao revisor, e "não verificada" é uma informação honesta. Provedor indisponível sobe como exceção, e a mensagem volta para a fila.
- **Trecho citado que sumiu.** Se a fonte foi reindexada e o trecho citado não existe mais, a resposta vai para `FAILED`: a fundamentação não pôde ser conferida.
- **Configuração.** `lexflow.pipeline.self-verification.enabled` liga a etapa e `question-keys` amplia a lista de perguntas críticas; em branco vale o conjunto mínimo da seção 10, item 4. Desligada, as respostas chegam ao revisor como `NOT_VERIFIED` — o que é diferente de esconder a etapa.

**Revisão humana e decisão (Prompt 15)**
- **Endpoints novos:**
  - `GET /api/v1/legal-cases?status=PENDING_HUMAN_REVIEW`: fila de revisão, paginada e em ordem de chegada;
  - `GET /api/v1/legal-cases/{id}/analysis`: cada pergunta respondida com resposta, confiança, origem, resultado da segunda checagem e o **texto completo** dos trechos citados, mais os alertas em aberto e as decisões já registradas;
  - `POST /api/v1/legal-cases/{id}/decisions`: registra a decisão, com suporte a `Idempotency-Key`;
  - `POST /api/v1/legal-cases/{id}/documents`: reenvio de documentação de uma demanda devolvida.
- **Identificação de quem decide.** Enquanto não há provedor de identidade, o responsável vem do cabeçalho `X-User-Id` (ou do corpo). **Isso identifica, mas não autentica**: qualquer cliente pode informar qualquer valor. A troca fica contida no controller e na `SecurityConfiguration`.
- **Decisão e transição são uma operação só.** Gravadas na mesma transação: decisão sem transição deixaria a demanda parada em revisão para sempre; transição sem decisão apagaria quem decidiu. Uma segunda decisão na mesma demanda é recusada pela máquina de estados (`409`).
- **Devolução exige comentários**, já no construtor de `Decision`: devolver sem dizer o que falta não ajuda ninguém.
- **Reabertura.** O reenvio de documentação devolve a demanda a `RECEIVED` e a deixa em condição de ser analisada de novo: as respostas anteriores da IA são descartadas (falavam de outra documentação), os alertas em aberto são resolvidos (é o reenvio que responde a eles) e o evento `LEGAL_CASE_RECEIVED` é republicado com novo identificador. Os documentos já anexados permanecem; um arquivo idêntico a um já anexado é ignorado.
- **Evento.** `DECISION_REGISTERED` é publicado na exchange `lexflow.events`, com a chave `legal-case.decision-registered`, e a fila `lexflow.decision-registered` já é declarada — sem fila ligada à exchange, o broker descartaria em silêncio os eventos publicados antes de existir consumidor. O evento leva identificadores e metadados, nunca o comentário da decisão.

**Trilha de auditoria (Prompt 16)**
- **A trilha observa; ela não participa.** A porta `AuditLogWriter` tem um contrato explícito: a implementação nunca lança. Uma falha ao gravar a linha aparece no log da aplicação e a operação observada segue — o contrário faria uma indisponibilidade da auditoria derrubar a ingestão de demandas.
- **Auditar não exigiu alterar nenhum caso de uso.** A gravação acontece em decoradores (`@Primary`) dos repositórios que já existiam:
  - `AuditingLegalCaseStatusHistoryRepository`: como a seção 4 exige que *toda* transição gere uma linha de histórico, auditar a gravação do histórico cobre, por construção, todas as transições — inclusive as que forem acrescentadas depois. O registro inicial, sem status anterior, é a criação da demanda;
  - `AuditingAiAnalysisResponseRepository`: cada resposta gravada, com ator `AI` quando veio do modelo e `SYSTEM` quando veio de regra de código; o segundo `save` da mesma resposta é o selo da verificação;
  - `AuditingDecisionRepository`: cada decisão humana, sem o comentário.
- **Consulta.** `GET /api/v1/legal-cases/{id}/audit-log` devolve a linha do tempo completa, da ação mais antiga para a mais recente. Uma demanda inexistente responde `404`, para que trilha vazia e demanda inexistente não se confundam.
- **Limite conhecido.** A decisão e a transição que ela provoca ocorrem na mesma transação e no mesmo instante: a ordem entre essas duas linhas não é determinística.

**Resiliência e idempotência (Prompt 17)**
- **O que faltava e foi acrescentado:** o storage tinha só tempos limite — ganhou retry e circuit breaker na instância `storage`, com as tentativas do SDK da AWS desligadas; o pool do banco ganhou tempos limite explícitos; a janela de novas tentativas do consumidor passou de ~7 s para mais de dois minutos, porque a anterior era menor que uma queda típica de provedor externo; e `processing_events` ganhou expiração.
- **Idempotência em todo endpoint de escrita que pode ser re-chamado:** ingestão, decisão, reenvio de documentação e indexação de fonte normativa. O CRUD de regras de checklist não usa chave porque a unicidade é do próprio dado (`case_type` + `required_document_type`).
- **Escopo da chave.** Todas convivem em um índice único só; o prefixo do `IdempotencyNamespace` é o que impede que a mesma chave, usada em dois endpoints, seja confundida com uma repetição.
- **Testes de caos.** `PipelineChaosIT` prova o critério de aceite: o LLM cai, volta, e a demanda retoma sozinha, sem dead-letter e sem processar nada duas vezes. A queda do banco não é automatizada — pausar o container derrubaria o contexto Spring compartilhado por toda a suíte —, e o comportamento esperado está no runbook.
- **Runbook.** `docs/resilience-runbook.md` lista cada ponto de falha, o que acontece, quando um operador precisa agir e como republicar mensagens da dead-letter.

**Observabilidade (Prompt 18)**
- **Correlação por demanda.** `LegalCaseCorrelation` amarra o `legalCaseId` em três lugares ao mesmo tempo: no MDC (todo log do trecho sai com ele), no baggage do trace (viaja pela fila até outra réplica) e como atributo do span (o trace fica pesquisável por demanda). O escopo é sempre fechado: um MDC que vaza faz a próxima demanda aparecer com o identificador da anterior, o que é pior do que não ter correlação.
- **Os dois lados são correlacionados.** O escopo é aberto no consumidor da fila e, nas requisições HTTP, pelo `LegalCaseCorrelationFilter`, que lê o identificador do caminho. Sem o filtro, a consulta da análise, o registro da decisão e a auditoria sairiam no log sem o identificador, e a história de uma demanda começaria só depois da fila. A criação (`POST /api/v1/legal-cases`) é a exceção natural: o identificador só existe ao fim da requisição, e a correlação dela começa no evento publicado.
- **Trace distribuído.** `observation-enabled` no template e no listener do RabbitMQ costura ingestão, fila e processamento em um trace só. A amostragem é de 10% em produção e zero em `dev`, onde não há coletor.
- **Métricas técnicas.** `lexflow.external.call` (latência e desfecho por integração: `llm`, `embeddings`, `storage`), `lexflow.queue.depth` (lido do broker por coleta periódica), `lexflow.legal_case.time_to_review` (por tipo de demanda) e `lexflow.legal_case.status_transitions`. Nenhuma etiqueta carrega conteúdo ou identificador de demanda — além de violar a seção 12, explodiria a cardinalidade das séries.
- **Onde a instrumentação mora.** Nas bordas: nos clientes externos e em um decorador do repositório de histórico (`Metered → Auditing → JPA`), pela mesma razão da auditoria — toda transição gera uma linha de histórico, então instrumentar a gravação cobre o pipeline inteiro sem tocar em nenhum caso de uso.
- **Métrica de negócio central.** A visão `ai_human_agreement` (migration V10) relaciona, por demanda decidida, a **sugestão implícita** da IA e o desfecho humano. A IA nunca emite essa sugestão: ela é uma leitura do conjunto das respostas — `FAVORABLE` quando todas as perguntas foram respondidas, nenhuma verificação reprovou, nenhuma declara ausência de fundamento, não há alerta em aberto e a menor confiança é de pelo menos 0,7; `UNFAVORABLE` quando qualquer um desses pontos falha; `INCONCLUSIVE` quando não há respostas. Concordar é a sugestão favorável corresponder a `APPROVED`, e a desfavorável, a qualquer outro desfecho.
- **Por que uma visão, e não uma tabela.** O dado já existe em `ai_analysis_responses`, `decisions` e `legal_case_alerts`. Duplicá-lo criaria uma terceira versão da verdade, que poderia divergir das outras duas — e a métrica deixaria de medir o sistema para medir a si mesma.
- **Endpoints.** `GET /api/v1/metrics/ai-human-agreement` e `GET /api/v1/metrics/dashboard`, ambos com papel `ADMIN`. Devolvem JSON para consumo por Grafana ou Metabase; o painel visual não é do sistema.
- **Log estruturado.** JSON no formato ECS apenas no perfil `prod`: em desenvolvimento, o log legível vale mais do que o log consultável.

**Dataset de regressão de prompts (Prompt 19)**
- **Para que serve.** Nenhum teste com dublê responde se mudar o texto de um prompt, ou trocar de modelo, piorou as respostas. O dataset roda o pipeline de IA (Prompts 11 a 14) contra casos dourados usando o provedor real e produz um relatório comparável entre execuções.
- **Nunca roda sozinho.** A tag `regression` é excluída de todas as tasks de teste comuns; só `./gradlew regressionTest` a inclui. As chamadas custam dinheiro e o modelo não é determinístico.
- **Um caso é uma pasta.** `lexflow-api/src/test/resources/golden-cases/<caso>/` com um `case.json` e as normas que ele indexa, mais uma linha em `index.txt`. Nenhum código precisa ser alterado para acrescentar um caso.
- **O gabarito não é o texto da resposta.** Comparar texto gerado com texto esperado reprovaria qualquer variação de redação e aprovaria uma resposta bem escrita com a conclusão errada. O que se declara são propriedades: estar fundamentada (`stance`), mencionar (`mustMention`) e não mencionar (`mustNotMention`) determinados termos, citar uma fonte específica (`mustCiteSource`), um piso de confiança e o resultado esperado da segunda checagem. Campo omitido não é conferido.
- **Fatos são conferidos como subconjunto.** O gabarito declara só o que importa naquele documento, e um campo novo no schema não quebra o dataset.
- **Documentos fictícios, sempre.** Nenhum documento real de cliente entra no dataset, nem anonimizado: um contrato anonimizado continua sendo o contrato de alguém.
- **Piso de 80%, e não 100%.** O modelo não é determinístico, e exigir perfeição faria o dataset falhar por ruído — o que ensinaria o time a ignorá-lo. O que se quer detectar é a direção entre duas execuções.
- **O relatório registra modelo e versões de prompt.** Sem isso, dois relatórios não diriam por que diferem, e a causa muda a conclusão: uma queda após trocar o texto do prompt se resolve revertendo o texto; após trocar de modelo, não.
- **A máquina do dataset é testada na suíte normal** (`GoldenCaseDatasetTest`), para que um carregador quebrado não apareça só durante uma execução paga.
- Como acrescentar casos e como ler o relatório: `docs/prompt-regression.md`.
