plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.ktlint) apply false
}

subprojects {
    repositories {
        mavenCentral()
    }

    // Apply ktlint only to Kotlin/JVM modules. Maven/Amper wrapper modules
    // (cowork-project, cowork-preference) build outside Gradle and must be skipped.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")

        extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            version.set("1.5.0")
        }
    }
}