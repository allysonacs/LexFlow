plugins {
    alias(libs.plugins.spring.boot) apply false
}

// Convenções compartilhadas por todos os módulos
subprojects {
    apply(plugin = "java-library")

    group = "com.lexflow"
    version = "0.0.1-SNAPSHOT"

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    dependencies {
        // O BOM entra só como restrição de versão nos testes; não adiciona Spring ao classpath
        "testImplementation"(platform(rootProject.libs.spring.boot.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testImplementation"(rootProject.libs.assertj.core)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    // A seção 13 da base de conhecimento exige 80% de cobertura em domain e application.
    // Nos demais módulos a garantia vem dos testes de integração, não de um percentual.
    if (name in listOf("lexflow-domain", "lexflow-application")) {
        apply(plugin = "jacoco")

        tasks.named<JacocoReport>("jacocoTestReport") {
            dependsOn(tasks.named("test"))
            reports {
                xml.required = true
                html.required = true
            }
        }

        tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
            violationRules {
                rule {
                    limit {
                        counter = "LINE"
                        minimum = "0.80".toBigDecimal()
                    }
                }
            }
        }

        tasks.named("check") {
            dependsOn("jacocoTestReport", "jacocoTestCoverageVerification")
        }
    }
}
