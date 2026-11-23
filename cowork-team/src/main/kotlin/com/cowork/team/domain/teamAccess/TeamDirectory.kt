package com.cowork.team.domain.teamAccess

import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

data class LockedTeam(val id: Long, val name: String, val ownerId: Long)

interface TeamDirectory {
    fun exists(teamId: Long): Boolean
    fun lock(teamId: Long): LockedTeam?

    fun requireExists(teamId: Long) {
        if (!exists(teamId)) throw ExpectedException("팀을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
    }

    fun lockOrThrow(teamId: Long): LockedTeam = lock(teamId)
        ?: throw ExpectedException("팀을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
}
