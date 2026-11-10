plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.ktlint)
}

group = "com.cowork"
version = "20261110.1"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.cloud.dependencies.get().toString())
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

ktlint {
    version.set(libs.versions.ktlint.asProvider())
}

// Keep ktlint's compiler dependencies independent of the Spring BOM.
configurations.matching { it.name.startsWith("ktlint") }.configureEach {
    resolutionStrategy.eachDependency {
        val requestedVersion = requested.version
        if (requested.group == "org.jetbrains.kotlin" && !requestedVersion.isNullOrBlank()) {
            useVersion(requestedVersion)
            because("Keep ktlint's compiler dependencies independent of the Spring BOM")
        }
    }
}

dependencies {
    // Reuse the response model without adding the SDK's MVC auto-configuration dependencies.
    implementation(libs.the.sdk) { isTransitive = false }
    // Spring APIs require UnknownNullability from the pinned annotations version.
    compileOnly(libs.jetbrains.annotations)

    implementation(libs.spring.cloud.starter.gateway)
    implementation(libs.spring.boot.starter.webclient)
    implementation(libs.spring.cloud.starter.circuitbreaker.reactor.resilience4j)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.spring.boot.starter.data.redis.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    implementation(libs.kotlin.reflect)
    implementation(libs.springdoc.openapi.webflux.ui)

    implementation(libs.logstash.logback.encoder)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
