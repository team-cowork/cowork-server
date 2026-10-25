plugins {
    id("cowork.spring-boot-conventions")
    alias(libs.plugins.spotless)
}

version = "20261025.0"

repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.data.r2dbc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.spring.cloud.starter.netflix.eureka.client)
    implementation(libs.spring.cloud.starter.config)
    implementation(libs.r2dbc.mysql)
    implementation(libs.logstash.logback.encoder)

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

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

// Spotless Java formatter configuration.
spotless {
    java {
        target("src/main/java/**/*.java", "src/test/java/**/*.java")
        eclipse().configFile("$projectDir/config/eclipse-java-formatter.xml")
        leadingTabsToSpaces(4)
        importOrder("java", "javax", "org", "com", "")
        removeUnusedImports()
        endWithNewline()
        trimTrailingWhitespace()
    }
}

tasks.named<Jar>("jar") {
    enabled = false
}
