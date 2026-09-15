# LexFlow

O LexFlow automatiza a primeira camada de análise de demandas jurídicas: recebe a documentação, classifica o tipo de demanda, confere o checklist documental e usa IA com uma base normativa (RAG) para apoiar a decisão de um responsável humano.

> A fonte de verdade sobre domínio, regras de negócio, convenções e arquitetura é o arquivo [`docs/00-knowledge-base.md`](docs/00-knowledge-base.md). Leia esse arquivo antes de contribuir.

## Stack

- Java 21 (com virtual threads habilitadas)
- Spring Boot 3.5
- Gradle 9 (Kotlin DSL), multi-módulo, com o wrapper versionado
- PostgreSQL + pgvector, Flyway (a partir do Prompt 03)
- Testcontainers para testes de integração

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

### Configuração base do Testcontainers

O módulo `lexflow-infrastructure` publica *test fixtures* com a classe `PostgresTestcontainersConfiguration`. Ela sobe um PostgreSQL com pgvector e conecta o Spring Boot a ele automaticamente, por meio de `@ServiceConnection`. Os testes de integração de qualquer módulo podem importar essa classe:

```java
@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
class MeuTesteDeIntegracao { }
```

Para rodar esses testes, o Docker precisa estar em execução.

## Como executar

Pré-requisito: um JDK instalado para rodar o Gradle. Se o Java 21 não estiver disponível, o toolchain do Gradle baixa essa versão automaticamente.

```bash
# Compila todos os módulos e roda os testes
./gradlew build

# Sobe a aplicação (o perfil padrão é o dev)
./gradlew :lexflow-api:bootRun

# Health check
curl http://localhost:8080/actuator/health
```

## Perfis e variáveis de ambiente

O arquivo de configuração é `lexflow-api/src/main/resources/application.yml`.

| Perfil | Uso |
|---|---|
| `dev` | Perfil padrão. Tem valores locais de fallback e mostra os detalhes do health check. |
| `prod` | Ative com `SPRING_PROFILES_ACTIVE=prod`. As credenciais vêm só de variáveis de ambiente, sem valores de fallback. |

| Variável | Descrição |
|---|---|
| `LEXFLOW_SERVER_PORT` | Porta HTTP (padrão `8080`) |
| `LEXFLOW_DB_URL` | URL JDBC do PostgreSQL |
| `LEXFLOW_DB_USERNAME` | Usuário do banco |
| `LEXFLOW_DB_PASSWORD` | Senha do banco |

## Documentação

- `docs/00-knowledge-base.md`: base de conhecimento do sistema
- `files/`: prompts de implementação, de `01` a `19`, a serem executados em ordem
