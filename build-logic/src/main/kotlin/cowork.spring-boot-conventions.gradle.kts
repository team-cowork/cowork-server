import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("cowork.jvm-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "com.cowork"

dependencyManagement {
    imports {
        mavenBom(libs.findLibrary("spring-cloud-dependencies").get().get().toString())
    }
}
