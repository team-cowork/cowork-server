plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}

group = "com.cowork"
version = "20260420.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    maven { url = uri("https://jitpack.io") }
}

val springdocVersion = libs.versions.springdoc.webmvc.get()

dependencyManagement {
    imports {
        mavenBom(libs.spring.cloud.dependencies.get().toString())
        mavenBom(libs.awspring.cloud.bom.get().toString())
    }
    dependencies {
        dependency("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")
        dependency("org.springdoc:springdoc-openapi-starter-webmvc-api:$springdocVersion")
        dependency("org.springdoc:springdoc-openapi-starter-common:$springdocVersion")
        dependency("org.springframework.cloud:spring-cloud-context:4.2.1")
        dependency("org.springframework.cloud:spring-cloud-commons:4.2.1")
        dependency("org.springframework.cloud:spring-cloud-starter:4.2.1")
        dependency("org.springframework.cloud:spring-cloud-starter-loadbalancer:4.2.1")
        dependency("org.springframework.cloud:spring-cloud-loadbalancer:4.2.1")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(libs.micrometer.registry.prometheus)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    implementation(libs.awspring.cloud.s3)
    implementation("com.mysql:mysql-connector-j")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation(libs.the.sdk) {
        exclude(group = "org.springframework.boot")
        exclude(group = "org.springframework.cloud")
        exclude(group = "org.springdoc")
    }
    implementation(libs.springdoc.openapi.webmvc.ui)
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
