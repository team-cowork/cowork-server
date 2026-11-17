package com.cowork.team.domain.teamRole.entity

enum class TeamRole {
    OWNER,

    // TODO(2026-11-17T06:59:44Z): Remove ADMIN after stored memberships and all consumers migrate to policy
    // assignments.
    ADMIN,
    MEMBER,
}
