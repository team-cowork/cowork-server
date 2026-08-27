package com.cowork.team.domain.teamRole.service.support

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class TeamRoleLookupSupport(private val teamRoleProjectionReader: TeamRoleProjectionReader) {
    fun findRoleOrThrow(teamId: Long, roleId: Long): TeamRoleResponse =
        teamRoleProjectionReader.findRole(teamId, roleId)
            ?: throw ExpectedException("역할을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
}
