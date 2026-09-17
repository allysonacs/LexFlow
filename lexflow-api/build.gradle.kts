plugins {
    alias(libs.plugins.spring.boot)
}

// Camada de entrada REST e ponto de inicialização da aplicação.
dependencies {
    implementation(project(":lexflow-infrastructure"))
    implementation(project(":lexflow-application"))
    implementation(project(":lexflow-domain"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    // Protege a API administrativa (Prompt 09); os demais endpoints seguem abertos até o prompt de segurança.
    implementation(libs.spring.boot.starter.security)
    // Observabilidade (Prompt 18): métricas em formato Prometheus e tracing distribuído por OpenTelemetry.
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.opentelemetry.exporter.otlp)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(testFixtures(project(":lexflow-infrastructure")))
    // Os testes de integração conferem no banco o que a API gravou; em produção o módulo continua
    // sem enxergar JPA, que chega apenas em tempo de execução, por lexflow-infrastructure.
    testImplementation(libs.spring.boot.starter.data.jpa)
    // Os testes de fila esperam um efeito assíncrono; sem isso restaria dormir por um tempo fixo.
    testImplementation(libs.awaitility)
    testImplementation(libs.spring.boot.starter.amqp)
    // O teste de contexto confere a configuração do Resilience4j lida do application.yml.
    testImplementation(libs.resilience4j.spring.boot3)
}

// Dataset de regressão de prompts (Prompt 19).
//
// Roda os golden cases contra o LLM e o provedor de embeddings de verdade, e por isso fica fora de
// `check` e de qualquer execução automática: as chamadas custam dinheiro e não são determinísticas.
// A exclusão da tag `regression` nas tasks de teste comuns está no build da raiz.
tasks.register<Test>("regressionTest") {
    group = "verification"
    description = "Roda os golden cases contra o LLM real e gera o relatório de regressão de prompts."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("regression")
    }
    // Nunca em cache: o objetivo é justamente comparar uma execução com a anterior.
    outputs.upToDateWhen { false }
    // A execução é longa e cheia de espera por rede; o log ao vivo é o que dá sinal de vida.
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
