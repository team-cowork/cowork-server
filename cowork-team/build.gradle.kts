plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}

group = "com.cowork"
version = "20260820.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencyManagement {
    imports {
        mavenBom(libs.awspring.cloud.bom.get().toString())
        // Keep the repository-wide Spring Cloud train authoritative. The AWS BOM also manages
        // Spring Cloud and otherwise downgrades Commons to a version that rejects Spring Boot 4.1.
        mavenBom(libs.spring.cloud.dependencies.get().toString())
    }
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.cloud.starter.netflix.eureka.client)
    implementation(libs.spring.cloud.starter.config)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.mysql.connector.j)
    implementation(libs.spring.boot.flyway)
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)
    implementation(libs.kotlin.reflect)
    implementation(libs.the.sdk) {
        exclude(group = "org.springframework.boot")
        exclude(group = "org.springframework.cloud")
        exclude(group = "org.springdoc")
    }
    implementation(libs.springdoc.openapi.webmvc.ui)

    implementation(libs.awspring.cloud.s3)
    implementation(libs.logstash.logback.encoder)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named("jar") {
    enabled = false
}
