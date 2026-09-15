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
}
