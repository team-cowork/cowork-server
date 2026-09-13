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

dependencies {
    // Spring APIs use UnknownNullability, which older transitive annotations jars lack.
    compileOnly(libs.findLibrary("jetbrains-annotations").get())
}
