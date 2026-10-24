// cowork-preference is built by the Kotlin Toolchain.
// module.yaml is the single source of truth for dependencies and compilation settings; this Gradle
// file is only a wrapper that keeps the module in the multi-module build (settings.gradle.kts).
// Every build action is delegated to the kotlin CLI.
//
// The checked-in kotlin/kotlin.bat wrapper pins the toolchain version for local builds, Docker and CI.
// KOTLIN_CLI can override the command; relative paths are resolved against this module.
plugins {
    base
}

group = "com.cowork"
version = "20261024.0"

val isWindows = System.getProperty("os.name").lowercase().contains("win")
val kotlinCli: String = providers.environmentVariable("KOTLIN_CLI")
    .orElse(layout.projectDirectory.file(if (isWindows) "kotlin.bat" else "kotlin").asFile.absolutePath)
    .map { command ->
        val expanded = when {
            command == "~" -> System.getProperty("user.home")
            command.startsWith("~/") || command.startsWith("~\\") ->
                System.getProperty("user.home") + command.drop(1)
            else -> command
        }
        if (expanded.contains('/') || expanded.contains('\\')) file(expanded).absolutePath else expanded
    }
    .get()

fun kotlinToolchainTask(name: String, vararg kotlinToolchainArgs: String, taskGroup: String, desc: String) =
    tasks.register<Exec>(name) {
        group = taskGroup
        description = desc
        workingDir = projectDir
        doFirst {
            // Validate only for explicit paths; PATH-resolved command names are left to the OS.
            if (kotlinCli.contains('/') || kotlinCli.contains('\\')) {
                val cli = file(kotlinCli)
                if (!cli.isFile) {
                    throw GradleException(
                        "Kotlin CLI not found at '${cli.absolutePath}'. Restore the project's kotlin wrapper " +
                            "or set KOTLIN_CLI to an existing executable."
                    )
                }
            }
        }
        if (isWindows && kotlinCli.endsWith(".bat", ignoreCase = true)) {
            commandLine("cmd", "/c", kotlinCli, *kotlinToolchainArgs)
        } else {
            commandLine(kotlinCli, *kotlinToolchainArgs)
        }
    }

kotlinToolchainTask("kotlinToolchainBuild", "build", taskGroup = "build", desc = "Compile from module.yaml (Kotlin Toolchain)")
val kotlinToolchainPackage = kotlinToolchainTask("kotlinToolchainPackage", "package", taskGroup = "build", desc = "Build the executable JAR (Kotlin Toolchain)")
val kotlinToolchainTest = kotlinToolchainTask("kotlinToolchainTest", "test", taskGroup = "verification", desc = "Run tests (Kotlin Toolchain)")
val kotlinToolchainClean = kotlinToolchainTask("kotlinToolchainClean", "clean", taskGroup = "build", desc = "Clean Kotlin Toolchain build output and caches")

// Packaging compiles the application, so assemble needs only one CLI invocation.
tasks.named("assemble") { dependsOn(kotlinToolchainPackage) }
tasks.named("check") { dependsOn(kotlinToolchainTest) }
tasks.named("clean") { dependsOn(kotlinToolchainClean) }

kotlinToolchainTask("run", "run", taskGroup = "application", desc = "Run the application (Kotlin Toolchain)")
