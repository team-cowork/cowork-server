package com.cowork.build

import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.TaskAction
import java.io.File
import java.nio.file.Path

@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun runKtlint(
    @Input(inferTaskDependency = false) moduleDirectory: Path,
    @Input classpath: Classpath,
    format: Boolean = false,
) {
    val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
    val command = mutableListOf(
        Path.of(System.getProperty("java.home"), "bin", javaName).toString(),
        "-cp",
        classpath.resolvedFiles.joinToString(File.pathSeparator),
        "com.pinterest.ktlint.Main",
    )
    if (format) command += "--format"
    command += listOf("src/**/*.kt", "build-plugins/**/*.kt")

    val exitCode = ProcessBuilder(command)
        .directory(moduleDirectory.toFile())
        .inheritIO()
        .start()
        .waitFor()
    check(exitCode == 0) { "ktlint failed with exit code $exitCode" }
}
