# Prompt 01 — Esqueleto do projeto LexFlow

Você é um engenheiro de software especialista em Java/Spring Boot, atuando como desenvolvedor sênior no projeto **LexFlow**.

Antes de qualquer coisa, leia o arquivo `docs/00-knowledge-base.md` deste repositório. Ele é a fonte de verdade sobre domínio, convenções, arquitetura e stack. Siga-o rigorosamente.

## Objetivo
Criar o esqueleto do projeto multi-módulo Gradle, sem nenhuma lógica de negócio ainda.

## Escopo
1. Projeto Gradle (Kotlin DSL) com Java 21, usando `gradlew`/`gradlew.bat` versionados.
2. Módulos: `lexflow-domain`, `lexflow-application`, `lexflow-infrastructure`, `lexflow-api`.
   - `lexflow-domain`: sem dependência de Spring ou qualquer framework.
   - `lexflow-application`: depende apenas de `lexflow-domain`.
   - `lexflow-infrastructure`: depende de `lexflow-application` e `lexflow-domain`; aqui entram Spring Data, Spring AMQP/Kafka, etc.
   - `lexflow-api`: depende de todos os módulos acima; contém o `@SpringBootApplication` e os controllers.
3. Configurar `application.yml` com perfis `dev` e `prod` (pode usar placeholders de variáveis de ambiente para credenciais).
4. Habilitar virtual threads (`spring.threads.virtual.enabled=true`).
5. Endpoint de health check (`/actuator/health`) via Spring Boot Actuator.
6. Configurar Testcontainers no módulo `lexflow-infrastructure` para testes de integração futuros (Postgres), mas sem escrever testes de integração ainda — apenas a dependência e configuração base.
7. `.gitignore` adequado para projeto Java/Gradle.
8. `README.md` na raiz explicando a estrutura de módulos (pode ser em português).

## Restrições
- Não implemente nenhuma entidade de domínio, controller de negócio ou integração externa real neste prompt — isso vem nos próximos.
- Pacote raiz: `com.lexflow`.
- Comentários e Javadoc em português; nomes de classes/pacotes em inglês.

## Critério de aceite
- `./gradlew build` compila com sucesso todos os módulos.
- A aplicação sobe (`./gradlew :lexflow-api:bootRun`) e `/actuator/health` responde `UP`.
