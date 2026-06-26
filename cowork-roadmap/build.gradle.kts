plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
    java
}

group = "com.cowork"
version = "20260623.0"

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
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.data.r2dbc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.spring.cloud.starter.netflix.eureka.client)
    implementation(libs.spring.cloud.starter.config)
    implementation(libs.spring.cloud.starter.loadbalancer)
    implementation(libs.r2dbc.mysql)

    // Flyway runs migrations over JDBC at startup; the R2DBC driver above serves runtime queries.
    // starter-jdbc supplies the JDBC DataSource; spring-boot-flyway is the Spring Boot 4 module that
    // carries FlywayAutoConfiguration (no longer bundled in spring-boot-autoconfigure).
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.flyway)
    runtimeOnly(libs.mysql.connector.j)
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)

    implementation(libs.the.sdk) {
        exclude(group = "org.springframework.boot")
        exclude(group = "org.springframework.cloud")
        exclude(group = "org.springdoc")
    }
    implementation(libs.springdoc.openapi.webflux.ui)
    implementation(libs.logstash.logback.encoder)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
}

// Spotless Java formatter configuration.
spotless {
    java {
        target("src/main/java/**/*.java", "src/test/java/**/*.java")
        eclipse().configFile("$projectDir/src/main/resources/eclipse-java-formatter.xml")
        leadingTabsToSpaces(4)
        importOrder("java", "javax", "org", "com", "")
        removeUnusedImports()
        endWithNewline()
        trimTrailingWhitespace()
    }
}

tasks.named("compileJava") {
    dependsOn("spotlessApply")
}

tasks.named("compileTestJava") {
    dependsOn("spotlessApply")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<Jar>("jar") {
    enabled = false
}
