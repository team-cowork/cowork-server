plugins {
    id("cowork.kotlin-conventions")
}

version = "20261025.0"

dependencies {
    implementation(libs.spring.cloud.starter.gateway)
    implementation(libs.spring.cloud.starter.netflix.eureka.client)
    implementation(libs.spring.cloud.starter.config)
    implementation(libs.spring.retry)
    implementation(libs.spring.cloud.starter.bus.kafka)
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
    systemProperty(
        "gateway.config.dir",
        rootProject.file("cowork-config/src/main/resources/configs").absolutePath,
    )
}
