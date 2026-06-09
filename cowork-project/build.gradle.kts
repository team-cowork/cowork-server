// cowork-project is built by Maven; pom.xml is the single source of truth for the build.
// This Gradle file is only a wrapper that keeps the module in the multi-module build
// (settings.gradle.kts) and delegates every build action to the bundled Maven wrapper (mvnw).
plugins {
    base
}

group = "com.cowork"
version = "20260602.0"

val isWindows = System.getProperty("os.name").lowercase().contains("win")
val mvnw = file(if (isWindows) "mvnw.cmd" else "mvnw").absolutePath

fun mvnTask(name: String, vararg mvnArgs: String, taskGroup: String, desc: String) =
    tasks.register<Exec>(name) {
        group = taskGroup
        description = desc
        workingDir = projectDir
        commandLine(mvnw, *mvnArgs)
    }

val mvnPackage = mvnTask("mvnPackage", "package", taskGroup = "build", desc = "Compile, test, and package the executable jar (Maven)")
val mvnClean = mvnTask("mvnClean", "clean", taskGroup = "build", desc = "Clean the Maven build output")

// Wire the delegating tasks into the base plugin lifecycle so existing commands
// like `:cowork-project:build` keep working (they now invoke Maven under the hood).
tasks.named("assemble") { dependsOn(mvnPackage) }
tasks.named("clean") { dependsOn(mvnClean) }

tasks.register<Exec>("run") {
    group = "application"
    description = "Run the application via the Spring Boot Maven plugin"
    workingDir = projectDir
    commandLine(mvnw, "spring-boot:run")
}