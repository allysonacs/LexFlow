# LexFlow

O LexFlow automatiza a primeira camada de análise de demandas jurídicas: recebe a documentação, classifica o tipo de demanda, confere o checklist documental e usa IA com uma base normativa (RAG) para apoiar a decisão de um responsável humano.

O princípio que guia o sistema: **a IA nunca decide sozinha**. Ela responde citando a fonte e o nível de confiança; a decisão é sempre de uma pessoa. Tudo que é regra determinística, como saber se um documento obrigatório está presente, é resolvido por código, nunca por LLM.

> A fonte de verdade sobre domínio, regras de negócio, convenções e arquitetura é o arquivo [`docs/00-knowledge-base.md`](docs/00-knowledge-base.md). Leia esse arquivo antes de contribuir.

## Stack

- Java 21 (com virtual threads habilitadas)
- Spring Boot 3.5
- Gradle 9 (Kotlin DSL), multi-módulo, com o wrapper versionado
- PostgreSQL 17 + pgvector, com migrations em Flyway
- Testcontainers para os testes de integração
- JaCoCo para a verificação de cobertura

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
| `document` | `Document`, `Sha256Checksum` |
| `checklist` | `ChecklistRule`, `DocumentChecklistItem`, `ChecklistItemStatus`, `DocumentChecklist` |
| `ai` | `AiExtractedFact`, `AiAnalysisResponse`, `ConfidenceScore`, `QuestionKey`, `VerificationStatus` |
| `decision` | `Decision`, `DecisionType` |
| `exception` | `DomainException` (base) e as exceções específicas de cada regra |

Duas regras que o domínio faz cumprir sozinho, sem depender de nenhuma camada externa:

- **Máquina de estados.** As transições permitidas ficam isoladas em `LegalCaseStatusTransitionRules`, transcritas da seção 4 da base de conhecimento. Qualquer outra transição lança `InvalidStatusTransitionException`. O teste cobre a matriz completa dos 81 pares de status, tanto nas regras do domínio quanto no serviço de aplicação que as usa.
- **Resposta da IA sem fonte é recusada.** O construtor de `AiAnalysisResponse` rejeita uma resposta sem `citedChunks`, a não ser que o texto declare que a informação não está na base normativa. Uma alucinação sem fonte não consegue nem ser instanciada.

### Aplicação (`lexflow-application`)

Casos de uso e portas. Também sem framework: depende apenas do domínio.

- **`LegalCaseStatusTransitionService`** é o **ponto único de mudança de status** do sistema. Nenhum outro componente — controller, consumer de fila, job ou repositório — pode alterar o `LegalCaseStatus` diretamente. O serviço valida a transição pelas regras do domínio e devolve, na mesma operação, a demanda já no novo status e o `LegalCaseStatusHistoryEntry` correspondente. Os dois andam juntos justamente para que nenhuma demanda mude de status sem deixar rastro no histórico.
- O serviço é puro: não conhece banco, fila nem HTTP, e não persiste nada. Quem o chama grava a demanda pelo seu repositório e o registro pela porta **`LegalCaseStatusHistoryRepository`**, de preferência na mesma transação. A implementação dessa porta, em cima de JPA, entra junto com os casos de uso que persistem a demanda.
- O relógio e o gerador de identificadores são injetados, o que torna cada transição verificável com horário fixo nos testes.

### Persistência (`lexflow-infrastructure`)

- **Migrations Flyway** em `src/main/resources/db/migration`. A `V1__init_schema.sql` cria as 13 tabelas da seção 6 da base de conhecimento, habilita a extensão `vector` e cria os índices, incluindo o índice vetorial HNSW em `knowledge_base_chunks(embedding)` e o índice único de `processing_events(idempotency_key)`.
- **Entidades JPA** em `persistence/entity`, separadas das entidades de domínio: o módulo `lexflow-domain` não tem nenhuma anotação de persistência. A conversão entre os dois mundos fica nos mappers de `persistence/mapper`.
- **Repositórios Spring Data** em `persistence/repository`, um por tabela.
- As colunas `jsonb` usam `@JdbcTypeCode(SqlTypes.JSON)` e o `embedding` usa o tipo `vector`, através do módulo `hibernate-vector`.

Nos testes, o Hibernate roda com `ddl-auto: validate`. Se uma entidade e uma migration divergirem, o contexto nem sobe — foi assim que uma divergência de tipo de coluna apareceu já na primeira execução.

## Como executar

Pré-requisitos: um JDK instalado para rodar o Gradle e o Docker em execução. Se o Java 21 não estiver disponível, o toolchain do Gradle baixa essa versão automaticamente.

```bash
# Sobe o PostgreSQL com pgvector para desenvolvimento local
docker compose up -d

# Compila todos os módulos, roda os testes e verifica a cobertura
./gradlew build

# Sobe a aplicação (o perfil padrão é o dev); o Flyway aplica as migrations na inicialização
./gradlew :lexflow-api:bootRun

# Health check
curl http://localhost:8080/actuator/health
```

Para encerrar o banco local: `docker compose stop` (ou `docker compose down -v`, que também apaga os dados).

## Testes

```bash
./gradlew test                          # todos os módulos
./gradlew :lexflow-domain:test          # testes unitários, não precisam de Docker
./gradlew :lexflow-application:test     # testes unitários, não precisam de Docker
./gradlew :lexflow-infrastructure:test  # testes de integração, precisam de Docker
```

Os testes de integração sobem um PostgreSQL com pgvector via Testcontainers, aplicam as migrations e validam o mapeamento das entidades contra o schema real. Como todos herdam de `AbstractPersistenceIT`, com a mesma configuração, o Spring reaproveita o contexto e o container entre as classes de teste.

O módulo `lexflow-infrastructure` publica *test fixtures* com a classe `PostgresTestcontainersConfiguration`, que os testes de qualquer módulo podem importar:

```java
@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
class MeuTesteDeIntegracao { }
```

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

## Convenções

- Identificadores (classes, pacotes, tabelas, colunas, enums) em **inglês**; comentários e Javadoc em **português**.
- Enums e status em `UPPER_SNAKE_CASE`; tabelas no plural e colunas no singular, em `snake_case`.
- Toda exceção de domínio estende `DomainException` e tem nome descritivo.
- Nenhuma regra de negócio na camada de persistência, e nenhuma anotação de framework no domínio.
- As versões das dependências ficam centralizadas em `gradle/libs.versions.toml`.

## Documentação

- `docs/00-knowledge-base.md`: base de conhecimento do sistema
- `files/`: prompts de implementação, de `01` a `19`, a serem executados em ordem
