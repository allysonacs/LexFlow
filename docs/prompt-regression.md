# LexFlow — Dataset de regressão de prompts

> Este documento responde a duas perguntas: **como acrescentar um caso ao dataset** e **como ler o
> relatório**. O porquê do dataset é mais curto: nenhum teste com dublê consegue dizer se mudar o
> texto de um prompt, ou trocar de modelo, piorou as respostas. Só uma execução contra o provedor
> real diz isso — e é por isso que ela custa dinheiro e roda só sob demanda.

---

## 1. Como rodar

```bash
export ANTHROPIC_API_KEY=...             # provedor de LLM
export LEXFLOW_EMBEDDINGS_API_KEY=...    # provedor de embeddings (ou VOYAGE_API_KEY)

./gradlew regressionTest
```

Pré-requisitos: Docker em execução (o dataset sobe PostgreSQL, MinIO e RabbitMQ) e o Tesseract
instalado, como nos demais testes de integração.

O relatório sai em `lexflow-api/build/reports/prompt-regression/`:

| Arquivo | Para quê |
|---|---|
| `report.json` | Comparar execuções. O formato é estável, e um `diff` entre dois arquivos mostra o que mudou |
| `report-<data>.json` | A mesma execução, com data no nome: é o histórico |
| `report.md` | Ler. Resumo, tabela por caso e o detalhe de cada divergência |

**Este dataset nunca roda no `./gradlew build` nem em CI.** A tag `regression` é excluída de todas as
tasks de teste comuns; só a task `regressionTest` a inclui. Isso é deliberado: as chamadas custam
dinheiro, e um modelo não é determinístico — um dataset que reprova por ruído ensina o time a
ignorá-lo.

---

## 2. Como acrescentar um caso

Um caso é uma pasta em `lexflow-api/src/test/resources/golden-cases/`, mais uma linha em
`golden-cases/index.txt`. Nenhum código Java precisa ser alterado — e isso é o que mantém o dataset
vivo: quem encontra uma resposta ruim em produção consegue transformá-la em caso de regressão sem
abrir uma classe.

```
golden-cases/
├── index.txt                      # uma linha por caso
└── contrato-alcada-superior/
    ├── case.json                  # a demanda e o gabarito
    └── politica-alcadas.md        # a norma que o caso indexa
```

### `case.json`

```json
{
  "id": "contrato-alcada-superior",
  "description": "Contrato com valor mensal acima da alçada do gerente",
  "caseType": "CONTRACT_SIGNING",
  "requester": "regressao",
  "knowledgeSources": [
    {"title": "Política de Alçadas (regressão)", "sourceType": "INTERNAL_POLICY", "file": "politica-alcadas.md"}
  ],
  "documents": [
    {
      "fixture": "contrato-texto-nativo.pdf",
      "documentType": "CONTRACT_DRAFT",
      "expectedFacts": {"specificFacts": {"contractObject": "Prestação de serviços"}}
    }
  ],
  "expectedAnswers": [
    {
      "questionKey": "CAN_SIGN_CONTRACT",
      "stance": "GROUNDED",
      "mustMention": ["diretor"],
      "mustNotMention": ["R$ 50.000,00"],
      "mustCiteSource": "Política de Alçadas (regressão)",
      "minConfidence": 0.4,
      "expectedVerification": "VERIFIED"
    }
  ]
}
```

- **`knowledgeSources`** são indexadas antes da análise e removidas ao fim. O caso é autossuficiente:
  ele não depende do que já houver na base normativa.
- **`documents.fixture`** aponta para um arquivo de `fixtures/documents`, compartilhado com os demais
  testes. Todos têm conteúdo fictício — **nenhum documento real de cliente entra aqui**, nem
  anonimizado: um contrato anonimizado continua sendo um contrato de alguém.
- **`expectedFacts`** é um **subconjunto**: só os campos declarados são conferidos. Isso permite um
  gabarito curto, com o que realmente importa naquele documento, sem que cada campo novo do schema
  quebre o dataset.

### O gabarito das respostas

**O gabarito não é o texto da resposta.** Comparar texto gerado com texto esperado reprovaria
qualquer variação de redação — e aprovaria uma resposta bem escrita com a conclusão errada. O que se
declara são as propriedades que a resposta precisa ter:

| Campo | O que exige |
|---|---|
| `stance` | `GROUNDED` (fundamentada, com trecho citado), `NOT_FOUND` (declara que a base não tem o assunto) ou `DETERMINISTIC` (resolvida por regra de código) |
| `mustMention` | Termos que precisam aparecer. É onde se fixa a conclusão: "diretor", "homologação", "insuficiente" |
| `mustNotMention` | Termos proibidos. **É aqui que se prendem alucinações conhecidas**: um valor, uma lei ou um prazo que não está em documento nenhum |
| `mustCiteSource` | Título da fonte que precisa estar entre as citadas — a resposta certa pela norma errada não é a resposta certa |
| `minConfidence` | Piso de confiança |
| `expectedVerification` | `VERIFIED`, `FAILED` ou `NOT_VERIFIED` (Prompt 14) |

Campos omitidos não são conferidos. Comece frouxo e aperte conforme o comportamento se estabilizar:
um gabarito exigente demais no primeiro dia vira um teste que ninguém consegue manter verde.

---

## 3. Como ler o relatório

O resumo traz, para a execução inteira:

| Número | O que ele diz |
|---|---|
| `correctQuestionRate` | Percentual de `question_key` que atenderam ao gabarito. **É a métrica principal** |
| `failedVerificationRate` | Percentual de respostas reprovadas na segunda checagem. Subiu? O modelo está afirmando mais do que os trechos sustentam |
| `factMatchRate` | Percentual dos campos de fatos conferidos que bateram. Cai quando a extração regride, antes de a análise regredir |
| `averageDurationMillis` | Tempo médio por caso. Uma alta grande costuma ser mudança de modelo ou de esforço de raciocínio |
| `passedCases` | Casos sem nenhuma divergência |

O relatório também registra **o modelo e as versões de prompt ativas**. Sem isso, dois relatórios não
diriam *por que* diferem — e a causa muda completamente a conclusão: uma queda depois de trocar o
texto do prompt se resolve revertendo o texto; depois de trocar de modelo, não.

### Comparando duas execuções

```bash
diff <(jq .summary build/reports/prompt-regression/report-20260917-120000.json) \
     <(jq .summary build/reports/prompt-regression/report.json)
```

O que interessa é a **direção**: uma execução isolada com 90% não diz nada; duas execuções, uma com
95% e outra com 78% depois de uma mudança de prompt, dizem tudo.

### Quando a execução falha

A task reprova quando algum caso não chega à revisão humana ou quando o percentual de perguntas
corretas fica abaixo de **80%**. O piso não é 100% de propósito: o modelo não é determinístico, e
exigir perfeição faria o dataset falhar por ruído.

O `report.md` traz, para cada caso reprovado, o que exatamente falhou — a pergunta, a expectativa
violada e o valor observado. Comece por ali.

---

## 4. O que o dataset **não** faz

- **Não substitui os testes automatizados.** A lógica do pipeline é coberta sem gastar uma chamada,
  com dublês, na suíte normal. A máquina do próprio dataset — o carregador, o avaliador e o relatório
  — também é testada lá (`GoldenCaseDatasetTest`), para que um carregador quebrado não apareça só
  durante uma execução paga.
- **Não mede qualidade jurídica.** Ele mede se a resposta atende ao que foi declarado como certo em
  um caso fictício. Quem diz se a resposta é juridicamente acertada continua sendo uma pessoa.
- **Não roda sozinho.** Nenhum agendamento, nenhum gatilho de CI. Rode antes de publicar uma mudança
  de prompt ou de modelo, e guarde o relatório junto da mudança.
