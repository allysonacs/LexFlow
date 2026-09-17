# LexFlow — Runbook de resiliência

> Este documento responde a uma pergunta só: **quando algo cai, o que acontece com as demandas que
> estavam em processamento?** Ele lista os pontos de falha conhecidos, o comportamento esperado de
> cada um e o que um operador precisa fazer — quando precisa fazer alguma coisa.
>
> A base é a seção 11 da [base de conhecimento](00-knowledge-base.md). Aqui está o detalhe
> operacional; lá, a regra.

---

## 1. Princípios que valem para todas as integrações

1. **Nada fica pendurado.** Toda chamada externa tem tempo limite explícito. Sem isso, uma
   dependência lenta prende threads até esgotá-las, e o sistema inteiro para por causa de um serviço
   que sequer respondeu "não".
2. **Quem repete é o Resilience4j.** As bibliotecas clientes (SDK da AWS, driver HTTP) têm as suas
   próprias tentativas, e elas foram desligadas onde era possível. Duas camadas de retry multiplicam
   as chamadas e tornam imprevisível o tempo total de uma falha.
3. **Falha de dependência ≠ falha de documento.** Provedor fora do ar é problema de ambiente: a
   exceção sobe, a mensagem volta para a fila e a demanda é retomada. Documento ilegível ou resposta
   do modelo fora do formato é problema daquele caso: ele é registrado, a demanda para e espera uma
   pessoa. Confundir os dois manda para a dead-letter o que deveria esperar um humano — e vice-versa.
4. **Repetir é seguro.** Toda etapa confere o que já foi feito antes de agir: a chave de idempotência
   em `processing_events`, o índice único de fatos por documento, o de resposta por pergunta e a
   chave de storage derivada do conteúdo.

---

## 2. Pontos de falha, um a um

### 2.1 Provedor de LLM (Anthropic)

| | |
|---|---|
| **Proteções** | Bulkhead de 16 chamadas por réplica, 180 s por tentativa, circuito de janela 20 (abre com 50% de falhas ou 80% de chamadas acima de 120 s, fica 30 s aberto), retry de 3 tentativas com backoff de 2 s a 20 s |
| **Instância Resilience4j** | `llm` |
| **Sintoma** | `LlmUnavailableException` no log do consumidor; latência alta; circuito aberto |
| **O que acontece** | A exceção sobe, o consumidor não marca o evento como processado e a mensagem é tentada de novo: 6 tentativas, com espera de 2 s, 6 s, 18 s, 54 s e 60 s — mais de dois minutos de janela. Uma queda de 30 segundos é atravessada sem intervenção |
| **Quando o operador entra** | Só se a queda passar da janela: aí a mensagem vai para `lexflow.legal-case-received.dlq`. A demanda **não** se perde — ela fica no status em que estava e o evento fica `FAILED` em `processing_events` |
| **Recuperação** | Republicar a mensagem da dead-letter na exchange `lexflow.events` com a chave `legal-case.received`. O reprocessamento não repete nada: documentos com fatos e perguntas já respondidas são pulados |
| **Sem chave de API** | A aplicação sobe normalmente e só as chamadas falham, com `LlmRequestRejectedException` — que **não** é repetida, porque tentar de novo não resolve configuração. Para operar sem LLM, desligue as etapas: `LEXFLOW_FACT_EXTRACTION_ENABLED=false` e `LEXFLOW_LEGAL_ANALYSIS_ENABLED=false` |

### 2.2 Provedor de embeddings

| | |
|---|---|
| **Proteções** | Bulkhead de 8 chamadas, 45 s por tentativa, circuito de janela 20, retry de 3 tentativas com backoff de 1 s a 10 s |
| **Instância Resilience4j** | `embeddings` |
| **Sintoma** | `EmbeddingUnavailableException` na indexação (`503` na API) ou no consumidor |
| **O que acontece** | Na indexação de uma norma, a requisição falha e pode ser reenviada — com a mesma `Idempotency-Key`, sem risco de indexar duas vezes. No pipeline, a exceção sobe e a mensagem volta para a fila |
| **Atenção** | Dimensão diferente da coluna `vector` vira `EmbeddingRequestRejectedException` e **não** é repetida: é erro de configuração do modelo, não falha transitória |

### 2.3 Storage de documentos (S3 / MinIO)

| | |
|---|---|
| **Proteções** | 30 s por chamada e 10 s por tentativa no cliente, retry de 3 tentativas com backoff de 500 ms a 5 s e circuito de janela 20 (15 s aberto). As tentativas do SDK da AWS estão desligadas |
| **Instância Resilience4j** | `storage` |
| **Sintoma** | `DocumentStorageException`; a API de ingestão responde `503` |
| **O que acontece** | Na ingestão, a requisição falha antes de gravar qualquer coisa — nenhuma demanda pela metade. O cliente reenvia, de preferência com a mesma `Idempotency-Key`. Na extração de texto, a exceção sobe e a mensagem volta para a fila |
| **Por que repetir é seguro** | A chave do objeto é derivada do conteúdo: a segunda tentativa grava exatamente onde a primeira teria gravado |
| **Objeto inexistente** | `DocumentNotFoundInStorageException` **não** é repetida nem conta para o circuito: é resposta do serviço, não falha dele |

### 2.4 Banco de dados (PostgreSQL)

| | |
|---|---|
| **Proteções** | 5 s para obter conexão do pool, 3 s de validação, conexões recicladas a cada 30 min e verificadas a cada 5 min |
| **Sintoma** | Erros de conexão em qualquer camada; `503`/`500` na API |
| **O que acontece** | A API responde erro e nada é gravado — as operações de escrita são transacionais, então não existe "meio gravado". No consumidor, a exceção sobe e a mensagem volta para a fila |
| **Recuperação** | Automática: o pool reabre as conexões quando o banco volta, e as mensagens em retry retomam. Se a queda passar da janela de tentativas, vale o mesmo procedimento da dead-letter |
| **Não é automatizado em teste** | Pausar o container do PostgreSQL derrubaria o contexto Spring compartilhado por toda a suíte de integração. O comportamento acima decorre do que já é testado: transação por operação e retomada por mensagem |

### 2.5 Fila (RabbitMQ)

| | |
|---|---|
| **Publicação** | Retry de 3 tentativas com backoff de 500 ms. Se o broker estiver fora, a ingestão falha **depois** de a demanda estar gravada: o caso existe em `RECEIVED` e nenhum evento foi publicado |
| **Recuperação da publicação** | Republicar o evento `LEGAL_CASE_RECEIVED` para as demandas paradas em `RECEIVED`. A chave de idempotência do evento é derivada do próprio evento, então um evento novo reprocessa sem duplicar trabalho já feito |
| **Consumo** | Com o consumidor fora do ar, as mensagens se acumulam na fila e são processadas quando ele volta. Nada se perde: a fila é durável e as mensagens são persistentes |
| **Dead-letter** | `lexflow.legal-case-received.dlq` e `lexflow.decision-registered.dlq`. Mensagem na dead-letter **é o sinal de que algo precisa de gente** |

### 2.6 OCR (Tesseract)

| | |
|---|---|
| **Sintoma** | `DocumentTextExtractionException` no consumidor; aviso na inicialização quando o executável não é encontrado |
| **O que acontece** | Tesseract ausente ou OCR com tempo esgotado são **problema de ambiente**: a exceção sobe e a mensagem volta para a fila. A ausência do Tesseract nunca vira "documento sem texto" em silêncio |
| **Recuperação** | Instalar o Tesseract com o pacote de idioma e deixar a mensagem ser tentada de novo |

---

## 3. Idempotência: onde ela está e o que ela promete

| Operação | Chave | Escopo gravado | O que a repetição faz |
|---|---|---|---|
| `POST /api/v1/legal-cases` | `Idempotency-Key` (cliente) | `legal-case-ingestion:` | Devolve `201` com a demanda original; os arquivos reenviados são descartados |
| `POST /api/v1/legal-cases/{id}/decisions` | `Idempotency-Key` (cliente) | `legal-case-decision:` | Devolve a decisão original, sem segunda linha nem segunda transição |
| `POST /api/v1/legal-cases/{id}/documents` | `Idempotency-Key` (cliente) | `legal-case-resubmission:` | Devolve o estado deixado pela primeira chamada, sem reabrir a demanda de novo |
| `POST /api/v1/knowledge-base/sources` | `Idempotency-Key` (cliente) | `knowledge-base-source:` | Devolve a fonte já indexada, sem gastar embeddings nem duplicar a norma na recuperação |
| Consumo de `LEGAL_CASE_RECEIVED` | Derivada do evento | `LEGAL_CASE_RECEIVED:{eventId}` | Entrega repetida é descartada com log, não com erro |
| `POST/PUT/DELETE /api/v1/admin/checklist-rules` | — | — | Não usa chave: a unicidade é do próprio dado (`case_type` + `required_document_type`). Criar a mesma regra duas vezes responde `409`, e alterar duas vezes com o mesmo corpo leva ao mesmo estado |

**Todas as chaves convivem em um índice único só**, em `processing_events`. O prefixo do escopo é o
que impede que a mesma chave, usada em dois endpoints diferentes, seja confundida com uma repetição.

### Retenção das chaves

`processing_events` ganha uma linha por ingestão, por decisão e por mensagem consumida, e nenhuma
delas é apagada pelo fluxo normal. Um job horário remove as chaves **já processadas** com mais de
30 dias (`lexflow.idempotency.retention`).

> **A retenção é um compromisso, não um detalhe.** Enquanto a chave existe, um reenvio é reconhecido
> como repetição; depois de apagada, o mesmo reenvio criaria uma demanda nova. Por isso o padrão fica
> bem acima da janela de novas tentativas de qualquer cliente razoável. Reduzi-lo é uma decisão
> consciente sobre esse risco.

Eventos `FAILED` e `IN_PROGRESS` nunca são removidos: o primeiro é evidência de um problema, e o
segundo pode estar em execução em outra réplica.

---

## 4. Procedimentos

### 4.1 Demanda parada em um status intermediário

1. Consulte `GET /api/v1/legal-cases/{id}` e veja os **alertas em aberto**.
   - Com alerta: a demanda espera uma pessoa, não uma nova tentativa. Trate o alerta — normalmente
     reenviando a documentação (`POST /api/v1/legal-cases/{id}/documents`), o que resolve os alertas
     e recomeça o pipeline.
   - Sem alerta: é falha de ambiente. Veja se há mensagem na dead-letter.
2. Consulte `GET /api/v1/legal-cases/{id}/audit-log` para saber até onde o caso chegou e quando.
3. Republique o evento se for o caso. O reprocessamento é seguro: cada etapa confere o que já foi
   feito.

### 4.2 Mensagens na dead-letter

1. Leia o motivo no log do consumidor (a mensagem original traz `messageId` com a chave de
   idempotência).
2. Corrija a causa — provedor fora do ar, Tesseract ausente, credencial vencida.
3. Republique as mensagens na exchange `lexflow.events` com a chave de roteamento original.
4. Confirme que as demandas avançaram e que nada foi duplicado: um registro de fatos por documento,
   uma resposta por pergunta.

### 4.3 Circuito aberto

Um circuito aberto (`llm`, `embeddings` ou `storage`) é **proteção funcionando**, não incidente por
si só: o sistema parou de bater em um serviço que não está respondendo. Ele volta sozinho ao estado
de meia-abertura depois da espera configurada. Investigue o serviço de destino, não o LexFlow.

---

## 5. O que os testes cobrem

| Teste | O que ele prova |
|---|---|
| `PipelineChaosIT` | O LLM cai, volta, e a demanda retoma sozinha — sem ir para a dead-letter e sem processar nada duas vezes. Com o consumidor fora do ar, as demandas esperam na fila e são processadas quando ele volta |
| `LegalCaseProcessingQueueIT` | Entrega duplicada é descartada; falha permanente vai para a dead-letter sem travar a fila; evento já processado que volta é ignorado |
| `ProcessingEventCleanupIT` | Chaves processadas e antigas são removidas em lotes; chaves recentes, falhas e em andamento permanecem |
| `AnthropicMessagesClientIT` | Retry, circuito abrindo e fechando, bulkhead e timeout do cliente LLM, contra um provedor simulado |
| `VoyageEmbeddingClientIT` | Retry de `429`, recusa de `400` sem nova tentativa e dimensão inesperada recusada |
| `S3DocumentStorageAdapterIT` | O adapter de storage contra um MinIO real |
