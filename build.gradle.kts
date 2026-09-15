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
}
