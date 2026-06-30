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
        @Suppress("UnnecessaryApplyExpression")
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            version.set("1.5.0")
        }

        // Spring 6.2+ API carries org.jetbrains.annotations.UnknownNullability, which the
        // outdated annotations jar pulled in transitively by the Kotlin stdlib lacks. Pin a
        // current version so Kotlin can resolve the inferred-type annotations.
        dependencies {
            add("compileOnly", libs.jetbrains.annotations)
        }
    }
}
