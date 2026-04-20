plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "com.cowork"
version = "20260420.0"

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
    implementation(libs.eureka.client)

    // Flyway (JDBC, 시작 시 블로킹 실행)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.postgresql)

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
