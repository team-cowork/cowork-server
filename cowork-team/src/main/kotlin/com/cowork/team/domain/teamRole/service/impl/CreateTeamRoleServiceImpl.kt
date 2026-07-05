package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.domain.teamRole.presentation.data.request.CreateTeamRoleRequest
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.service.CreateTeamRoleService
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import com.cowork.team.global.client.PreferenceTeamRoleClient
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional(readOnly = true)
class CreateTeamRoleServiceImpl(
    private val preferenceTeamRoleClient: PreferenceTeamRoleClient,
    private val teamRoleAccessGuard: TeamRoleAccessGuard,
) : CreateTeamRoleService {

    override fun createRole(actorId: Long, teamId: Long, request: CreateTeamRoleRequest): TeamRoleResponse {
        val actor = teamRoleAccessGuard.requireManageRoles(teamId, actorId)
        if (actor.member.role != TeamRole.OWNER && actor.member.role != TeamRole.ADMIN) {
            if (request.priority >= actor.maxPriority) {
                throw ExpectedException("자신의 최상위 역할보다 높거나 같은 역할은 만들 수 없습니다.", HttpStatus.FORBIDDEN)
            }
        }
        return preferenceTeamRoleClient.createRole(teamId, request)
    }
}
