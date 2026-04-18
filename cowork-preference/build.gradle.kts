plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "com.cowork"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

val vertxVersion = libs.versions.vertx.get()
val coroutinesVersion = libs.versions.coroutines.get()

dependencies {
    implementation(platform("io.vertx:vertx-stack-depchain:$vertxVersion"))
    implementation("io.vertx:vertx-core")
    implementation("io.vertx:vertx-web")
    implementation("io.vertx:vertx-lang-kotlin")
    implementation("io.vertx:vertx-lang-kotlin-coroutines")
    implementation("io.vertx:vertx-config")
    implementation("io.vertx:vertx-pg-client")
    implementation("io.vertx:vertx-redis-client")
    implementation("io.vertx:vertx-kafka-client")
    implementation("io.vertx:vertx-service-discovery")
    // TODO: Eureka 통합 구현 시 HTTP Client로 직접 Eureka REST API 연동 필요
    //       vertx-service-discovery-backend-eureka는 Vert.x 4.x에서 별도 artifact 미제공

    // Flyway (JDBC, 시작 시 블로킹 실행)
    implementation("org.flywaydb:flyway-core:10.15.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.15.0")
    implementation("org.postgresql:postgresql:42.7.3")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    testImplementation(kotlin("test"))
    testImplementation("io.vertx:vertx-junit5:$vertxVersion")
}

application {
    mainClass.set("com.cowork.preference.CoworkPreferenceMainKt")
}

kotlin {
    compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict") }
}

tasks.withType<Test> { useJUnitPlatform() }
