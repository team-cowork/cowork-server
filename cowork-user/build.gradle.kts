plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    id("org.jetbrains.kotlin.plugin.jpa") version "2.1.20"
}

group = "com.cowork"
version = "0.0.1-SNAPSHOT"

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
    dependencies {
        dependency("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")
        dependency("org.springdoc:springdoc-openapi-starter-webmvc-api:2.7.0")
        dependency("org.springdoc:springdoc-openapi-starter-common:2.7.0")
        dependency("org.springframework.cloud:spring-cloud-context:4.2.1")
        dependency("org.springframework.cloud:spring-cloud-commons:4.2.1")
        dependency("org.springframework.cloud:spring-cloud-starter:4.2.1")
        dependency("org.springframework.cloud:spring-cloud-starter-loadbalancer:4.2.1")
        dependency("org.springframework.cloud:spring-cloud-loadbalancer:4.2.1")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    implementation(libs.awspring.cloud.s3)
    implementation("com.mysql:mysql-connector-j")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.github.themoment-team:the-sdk:1.5") {
        exclude(group = "org.springframework.cloud")
    }
    implementation("org.springframework.kafka:spring-kafka")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
