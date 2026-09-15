plugins {
    // Permite ao Gradle baixar automaticamente o JDK 21 exigido pelo toolchain
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "lexflow"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include(
    "lexflow-domain",
    "lexflow-application",
    "lexflow-infrastructure",
    "lexflow-api",
)
