package com.cowork.team.domain.teamAccess

import java.time.Instant

interface MembershipDirectory {
    fun exists(teamId: Long, userId: Long): Boolean
    fun memberIds(teamId: Long): Set<Long>
    fun activeVersionForUpdate(teamId: Long, userId: Long): Instant
}
