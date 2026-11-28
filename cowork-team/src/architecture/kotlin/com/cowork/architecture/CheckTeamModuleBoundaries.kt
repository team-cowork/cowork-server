package com.cowork.architecture

import org.springframework.modulith.core.ApplicationModuleDetectionStrategy
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.core.JavaPackage
import java.util.stream.Stream

class TeamModuleDetection : ApplicationModuleDetectionStrategy {
    // Each domain is a module. Infrastructure stays in the top-level global module.
    override fun getModuleBasePackages(rootPackage: JavaPackage): Stream<JavaPackage> =
        rootPackage.directSubPackages.stream().flatMap {
            if (it.localName == "domain") it.directSubPackages.stream() else Stream.of(it)
        }
}

fun main() {
    ApplicationModules.of("com.cowork.team").verify()
}
