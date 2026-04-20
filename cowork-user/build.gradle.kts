plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
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

val springdocWebmvcVersion = libs.versions.springdocWebmvc.get()
val springCloudComponentsVersion = libs.versions.springCloudComponents.get()

dependencyManagement {
    imports {
        mavenBom(libs.spring.cloud.dependencies.get().toString())
        mavenBom(libs.awspring.cloud.bom.get().toString())
    }
    dependencies {
        dependency("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocWebmvcVersion")
        dependency("org.springdoc:springdoc-openapi-starter-webmvc-api:$springdocWebmvcVersion")
        dependency("org.springdoc:springdoc-openapi-starter-common:$springdocWebmvcVersion")
        dependency("org.springframework.cloud:spring-cloud-context:$springCloudComponentsVersion")
        dependency("org.springframework.cloud:spring-cloud-commons:$springCloudComponentsVersion")
        dependency("org.springframework.cloud:spring-cloud-starter:$springCloudComponentsVersion")
        dependency("org.springframework.cloud:spring-cloud-starter-loadbalancer:$springCloudComponentsVersion")
        dependency("org.springframework.cloud:spring-cloud-loadbalancer:$springCloudComponentsVersion")
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
