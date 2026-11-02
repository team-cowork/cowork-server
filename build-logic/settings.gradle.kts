dependencyResolutionManagement {
    // 9th cohort, Jae-hyun Bae, 2026-11-02.
    // IntelliJ IDEA repeatedly reports this warning, but this configuration works with Gradle 9.8.2.
    // Temporarily suppress the warning; remove this suppression later.
    // Always run a build after any Gradle version change to verify that this suppression is still valid.
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
