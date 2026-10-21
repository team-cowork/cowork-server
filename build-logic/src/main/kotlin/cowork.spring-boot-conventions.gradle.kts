import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "com.cowork"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion.toInt())
    }
}

dependencyManagement {
    imports {
        mavenBom(libs.findLibrary("spring-cloud-dependencies").get().get().toString())
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
