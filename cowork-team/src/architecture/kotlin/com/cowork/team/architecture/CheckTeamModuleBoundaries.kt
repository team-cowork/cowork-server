package com.cowork.team.architecture

import org.springframework.modulith.core.ApplicationModules

fun main() {
    val modules = ApplicationModules.of("com.cowork.team.domain")
    val invites = modules.getModuleByName("teamInvite").orElseThrow()
    val members = modules.getModuleByName("teamMember").orElseThrow()

    val memberToInvite = members.getDirectDependencies(modules).stream()
        .filter { it.targetModule == invites }
    val inviteToMemberInternals = invites.getDirectDependencies(modules).stream()
        .filter { it.targetModule == members && !members.isExposed(it.targetType) }

    val violations = (memberToInvite.toList() + inviteToMemberInternals.toList())
        .map { "${it.sourceType.name} -> ${it.targetType.name}" }
        .distinct()
        .sorted()
    check(violations.isEmpty()) {
        violations.joinToString("\n", prefix = "Invalid team module dependencies:\n")
    }
}
