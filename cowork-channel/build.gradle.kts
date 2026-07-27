plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}

group = "com.cowork"
version = "20260727.0"

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
        mavenBom(libs.spring.cloud.dependencies.get().toString())
    }
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.restclient)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.cloud.starter.netflix.eureka.client)
    implementation(libs.spring.cloud.starter.config)
    implementation(libs.spring.cloud.starter.openfeign)
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
    implementation(libs.logstash.logback.encoder)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.provider.jdbc.template)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
    testImplementation("io.kotest:kotest-runner-junit5-jvm:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core-jvm:5.9.1")
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
