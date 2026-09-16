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
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.amqp)
    implementation(libs.hibernate.vector)
    implementation(platform(libs.awssdk.bom))
    implementation(libs.awssdk.s3)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesImplementation(libs.spring.boot.test)
    testFixturesApi(libs.spring.boot.testcontainers)
    testFixturesApi(libs.testcontainers.junit.jupiter)
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesApi(libs.testcontainers.minio)
    testFixturesApi(libs.testcontainers.rabbitmq)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(platform(libs.awssdk.bom))
    testImplementation(libs.awssdk.s3)
}
