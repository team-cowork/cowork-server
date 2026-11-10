plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
}

group = "com.cowork"
version = "20261109.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.logstash.logback.encoder)

    implementation(libs.spring.boot.flyway)
    runtimeOnly(libs.mysql.connector.j)
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)

    implementation(libs.the.sdk) {
        // Use the SpringDoc version selected for this module's Spring Boot version.
        exclude(group = "org.springdoc")
    }
    implementation(libs.springdoc.openapi.webmvc.ui)

    testImplementation(libs.spring.boot.starter.test)

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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
