// cowork-preference is built by the Kotlin Toolchain (Amper).
// module.yaml is the single source of truth for dependencies and compilation settings; this Gradle
// file is only a wrapper that keeps the module in the multi-module build (settings.gradle.kts).
// Every build action is delegated to the kotlin CLI.
//
// The kotlin CLI location can be overridden via the KOTLIN_CLI environment variable; it defaults to
// the install-script path ~/.local/bin/kotlin.
plugins {
    base
}

group = "com.cowork"
version = "20260623.0"

// On Windows the Kotlin CLI is installed as kotlin.bat.
val isWindows = System.getProperty("os.name").lowercase().contains("win")
val kotlinCli: String = providers.environmentVariable("KOTLIN_CLI")
    .orElse(
        providers.systemProperty("user.home")
            .map { "$it/.local/bin/kotlin${if (isWindows) ".bat" else ""}" }
    )
    .get()

fun amperTask(name: String, vararg amperArgs: String, taskGroup: String, desc: String) =
    tasks.register<Exec>(name) {
        group = taskGroup
        description = desc
        workingDir = projectDir
        doFirst {
            // Validate only for explicit paths; PATH-resolved command names are left to the OS.
            if (kotlinCli.contains('/') || kotlinCli.contains('\\')) {
                // Gradle's file() does not expand a leading '~', so expand it explicitly.
                val expanded = if (kotlinCli.startsWith("~")) {
                    kotlinCli.replaceFirst("~", System.getProperty("user.home"))
                } else {
                    kotlinCli
                }
                val cli = file(expanded)
                if (!cli.exists()) {
                    throw GradleException(
                        "Kotlin CLI not found at '${cli.absolutePath}'. Install the Kotlin Toolchain " +
                            "(Amper) or set the KOTLIN_CLI environment variable to its location."
                    )
                }
            }
        }
        commandLine(kotlinCli, *amperArgs)
    }

val amperBuild = amperTask("amperBuild", "build", taskGroup = "build", desc = "Compile from module.yaml (Amper)")
val amperPackage = amperTask("amperPackage", "package", taskGroup = "build", desc = "Build the executable JAR (Amper)")
val amperTest = amperTask("amperTest", "test", taskGroup = "verification", desc = "Run tests (Amper)")
val amperClean = amperTask("amperClean", "clean", taskGroup = "build", desc = "Clean Amper build output and caches")


tasks.named("assemble") { dependsOn(amperBuild, amperPackage) }
tasks.named("check") { dependsOn(amperTest) }
tasks.named("clean") { dependsOn(amperClean) }

tasks.register<Exec>("run") {
    group = "application"
    description = "Run the application (Amper)"
    workingDir = projectDir
    commandLine(kotlinCli, "run")
}
