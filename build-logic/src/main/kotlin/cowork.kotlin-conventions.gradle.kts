import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("cowork.jvm-conventions")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jlleitschuh.gradle.ktlint")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

ktlint {
    version.set(libs.findVersion("ktlint").get().requiredVersion)
}

// Spring's dependency management also applies to tool configurations. Ktlint must retain
// the Kotlin compiler API it was built against instead of the application's Kotlin version.
configurations.matching { it.name.startsWith("ktlint") }.configureEach {
    resolutionStrategy.eachDependency {
        val requestedVersion = requested.version
        if (requested.group == "org.jetbrains.kotlin" && !requestedVersion.isNullOrBlank()) {
            useVersion(requestedVersion)
            because("Keep ktlint's compiler dependencies independent of the Spring BOM")
        }
    }
}

dependencies {
    // Spring APIs use UnknownNullability, which older transitive annotations jars lack.
    compileOnly(libs.findLibrary("jetbrains-annotations").get())
}
