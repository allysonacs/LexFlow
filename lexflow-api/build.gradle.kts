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

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(testFixtures(project(":lexflow-infrastructure")))
    // Os testes de integração conferem no banco o que a API gravou; em produção o módulo continua
    // sem enxergar JPA, que chega apenas em tempo de execução, por lexflow-infrastructure.
    testImplementation(libs.spring.boot.starter.data.jpa)
    // Os testes de fila esperam um efeito assíncrono; sem isso restaria dormir por um tempo fixo.
    testImplementation(libs.awaitility)
    testImplementation(libs.spring.boot.starter.amqp)
}
