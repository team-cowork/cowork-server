plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    id("cowork.kotlin-conventions")
}

group = "com.cowork"
version = "20260911.0"

dependencyManagement {
    imports {
        mavenBom(libs.spring.cloud.dependencies.get().toString())
    }
}

dependencies {
    implementation(libs.spring.cloud.config.server)
    implementation(libs.spring.cloud.starter.netflix.eureka.server)
    implementation(libs.spring.cloud.starter.bus.kafka)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.spring.vault.core)
    implementation(libs.kotlin.reflect)

    implementation(libs.logstash.logback.encoder)
    testImplementation(libs.spring.boot.starter.test)
}

tasks.named("jar") {
    enabled = false
}
