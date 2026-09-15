plugins {
    // Cobertura mínima de 80% exigida pela seção 13 da base de conhecimento
    jacoco
}

// Núcleo do domínio: Java puro, sem nenhuma dependência de framework (seção 7 da base de conhecimento).

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification)
}
