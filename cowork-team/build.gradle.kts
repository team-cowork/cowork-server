plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}

group = "com.cowork"
version = "20260420.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.cloud.dependencies.get().toString())
        mavenBom(libs.awspring.cloud.bom.get().toString())
    }
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.cloud.starter.netflix.eureka.client)
    implementation(libs.spring.cloud.starter.config)
    implementation(libs.spring.cloud.starter.openfeign)
    implementation(libs.spring.kafka)
    implementation(libs.mysql.connector.j)
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
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
