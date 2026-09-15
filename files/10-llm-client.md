# Prompt 10 — Cliente LLM isolado

Leia `docs/00-knowledge-base.md`, seções 7, 8, 10 e 11.

## Objetivo
Criar um adapter isolado para chamar a API do provedor de LLM, sem nenhuma lógica de negócio jurídica dentro dele.

## Escopo
1. No módulo `lexflow-infrastructure`, pacote `llm`, criar a porta `LlmClientPort` (definida em `lexflow-application`) com um método genérico, por exemplo `complete(LlmRequest request): LlmResponse`, onde `LlmRequest` contém: prompt, schema esperado de saída (opcional), modelo, parâmetros (temperatura, max tokens).
2. Implementação concreta chamando a API do provedor (ex.: Anthropic Messages API) via `WebClient`, com:
   - Timeout explícito.
   - Serialização/deserialização de structured output (JSON).
   - Registro de qual modelo/versão foi usado em cada chamada.
3. Aplicar Resilience4j: `CircuitBreaker`, `Retry` com backoff exponencial e `TimeLimiter` nesta chamada — configuráveis via `application.yml`.
4. Tratamento de erro: se o LLM retornar algo que não valida contra o schema esperado, lançar `LlmResponseValidationException` (não deixar dado inválido seguir adiante no pipeline).
5. Testes: usar um mock/stub do provedor (WireMock) para simular respostas válidas, respostas malformadas, timeout e erro 5xx, verificando que o circuit breaker e o retry se comportam como esperado.

## Restrições
- Esta classe não sabe nada sobre `LegalCase`, perguntas jurídicas ou RAG — é um cliente HTTP genérico e resiliente.
- Nunca logar o prompt completo em nível `INFO` (pode conter dados sensíveis) — usar `DEBUG` com cuidado ou mascarar.
- Comentários/Javadoc em português.

## Critério de aceite
- Uma falha simulada do provedor (timeout, 500) não derruba a aplicação nem trava outras requisições — o circuit breaker abre conforme configurado e volta a fechar depois.
