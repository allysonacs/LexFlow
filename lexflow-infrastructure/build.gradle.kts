plugins {
    // Expõe a configuração base do Testcontainers para os testes de outros módulos (ex.: lexflow-api)
    `java-test-fixtures`
}

// Adapters que implementam as portas da aplicação (JPA, fila, storage, LLM, RAG).
dependencies {
    api(project(":lexflow-application"))
    api(project(":lexflow-domain"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter)

    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesImplementation(libs.spring.boot.test)
    testFixturesApi(libs.spring.boot.testcontainers)
    testFixturesApi(libs.testcontainers.junit.jupiter)
    testFixturesApi(libs.testcontainers.postgresql)
}
