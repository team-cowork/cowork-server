package com.cowork.team.domain.teamRole.service.support

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.global.client.PreferenceTeamRoleClient
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class TeamRoleLookupSupport(private val preferenceTeamRoleClient: PreferenceTeamRoleClient) {

    fun findRoleOrThrow(teamId: Long, roleId: Long): TeamRoleResponse =
        preferenceTeamRoleClient.getRoles(teamId).firstOrNull { it.id == roleId }
            ?: throw ExpectedException("역할을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
}
