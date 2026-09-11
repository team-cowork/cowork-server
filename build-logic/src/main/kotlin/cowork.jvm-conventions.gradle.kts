import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    java
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion.toInt())
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
