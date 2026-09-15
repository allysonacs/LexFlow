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

---

## 6. Modelo de dados (nomes de tabela em inglês)

```sql
legal_cases (
  id, external_reference, case_type, status,
  requester, priority, created_at, updated_at
)

legal_case_status_history (
  id, legal_case_id, previous_status, new_status,
  changed_at, changed_by, reason
)

documents (
  id, legal_case_id, file_name, storage_path, mime_type,
  checksum_sha256, uploaded_at
)

checklist_rules (
  id, case_type, required_document_type, description, mandatory
)

document_checklist_items (
  id, legal_case_id, checklist_rule_id, status, document_id, evaluated_at
)
-- status: PENDING | SATISFIED | MISSING

ai_extracted_facts (
  id, legal_case_id, document_id, extracted_json, model_version, extracted_at
)

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

`embedding` usa o tipo `vector` da extensão `pgvector`. `extracted_json`, `cited_chunks` e `payload` são colunas `jsonb`.

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
- **Mensageria:** Kafka (ou RabbitMQ — decidir no Prompt 7) para processamento assíncrono
- **Storage de arquivos:** S3 (ou MinIO em dev)
- **Cliente HTTP para LLM:** WebClient (Spring) ou HTTP client nativo do Java 21
- **Resiliência:** Resilience4j (circuit breaker, retry, timeout, bulkhead)
- **Observabilidade:** Micrometer + OpenTelemetry
- **Testes:** JUnit 5, Testcontainers (Postgres, Kafka)

---

## 9. Regras de negócio do checklist documental

- Cada `LegalCaseType` tem um conjunto de `ChecklistRule` (configurável em banco, não hardcoded).
- Ao classificar uma demanda, o sistema gera os `DocumentChecklistItem` correspondentes às regras do tipo.
- Um item fica `MISSING` até que um documento do tipo exigido seja vinculado; então passa a `SATISFIED`.
- `HAS_SUFFICIENT_DOCUMENTATION` só pode ser respondida como suficiente se **todos** os itens obrigatórios (`mandatory = true`) estiverem `SATISFIED`.
- Regras devem ser avaliadas de forma **determinística e testável isoladamente**, sem chamar o LLM.

---

## 10. Estratégia de IA (anti-alucinação)

Fluxo em cadeia (prompt chaining), nunca uma única chamada monolítica:

1. **Extração estruturada** (`AiExtractedFact`) — o modelo extrai fatos do documento em JSON. Sem opinião jurídica nesta etapa.
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

---

## 12. Segurança e LGPD

- Dados de demandas jurídicas são sensíveis — controle de acesso por papel (ex.: `ANALYST`, `LEGAL_REVIEWER`, `ADMIN`).
- Nunca logar conteúdo integral de documentos, apenas metadados e hashes.
- Avaliar política de retenção de dados do provedor de LLM antes de enviar documentos com dados pessoais.
- `AuditLog` é append-only — nenhuma linha pode ser alterada ou apagada por código de aplicação.

---

## 13. Convenções de código

- Comentários de código e Javadoc: **português**.
- Nomes de classes, métodos, variáveis, tabelas, enums, tópicos: **inglês**.
- Toda exceção de domínio deve estender uma `DomainException` base e ter nome descritivo (`InvalidStatusTransitionException`, `ChecklistRuleNotFoundException`).
- Toda entidade de domínio é imutável sempre que possível (usar métodos que retornam novo estado em vez de setters, quando fizer sentido).
- Cobertura de teste mínima esperada para `domain` e `application`: 80%.
