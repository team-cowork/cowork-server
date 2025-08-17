package com.cowork.team.domain

enum class TeamMemberRole {
    OWNER,
    ADMIN,
    MEMBER,
    ;

    val isManager: Boolean get() = this == OWNER || this == ADMIN
}
