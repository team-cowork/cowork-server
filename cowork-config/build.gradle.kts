plugins {
    id("cowork.kotlin-conventions")
}

version = "20261023.0"

dependencies {
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.cloud.config.server)
    implementation(libs.spring.cloud.starter.netflix.eureka.server)
    implementation(libs.spring.cloud.starter.bus.kafka)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.spring.vault.core)
    implementation(libs.kotlin.reflect)

    implementation(libs.logstash.logback.encoder)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.named("jar") {
    enabled = false
}
