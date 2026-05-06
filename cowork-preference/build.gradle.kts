plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "com.cowork"
version = "20260420.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    implementation(platform(libs.vertx.stack.depchain))
    implementation(libs.vertx.core)
    implementation(libs.vertx.web)
    implementation(libs.vertx.lang.kotlin)
    implementation(libs.vertx.lang.kotlin.coroutines)
    implementation(libs.vertx.config)
    implementation(libs.vertx.pg.client)
    implementation(libs.scram.client)
    implementation(libs.vertx.redis.client)
    implementation(libs.vertx.kafka.client)
    implementation(libs.vertx.service.discovery)
    implementation(libs.vertx.micrometer.metrics)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.eureka.client)

    // Flyway (JDBC, 시작 시 블로킹 실행)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.postgresql)

    // JSON 로깅
    implementation(libs.log4j.core)
    implementation(libs.log4j.layout.template.json)
    implementation(libs.log4j.slf4j2.impl)

    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.vertx.junit5)
}

application {
    mainClass.set("com.cowork.preference.CoworkPreferenceMainKt")
}

kotlin {
    compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict") }
}

tasks.withType<Test> { useJUnitPlatform() }
