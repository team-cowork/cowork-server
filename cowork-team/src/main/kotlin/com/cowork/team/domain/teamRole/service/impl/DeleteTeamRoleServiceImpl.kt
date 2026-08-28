package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.operation.TeamRoleCommandSubmission
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandType
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleOperationResponse
import com.cowork.team.domain.teamRole.service.DeleteTeamRoleService
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import com.cowork.team.domain.teamRole.service.support.TeamRoleLookupSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DeleteTeamRoleServiceImpl(
    private val teamRoleAccessGuard: TeamRoleAccessGuard,
    private val teamRoleLookupSupport: TeamRoleLookupSupport,
    private val commandSubmission: TeamRoleCommandSubmission,
) : DeleteTeamRoleService {
    @Transactional
    override fun execute(
        actorId: Long,
        teamId: Long,
        roleId: Long,
        idempotencyKey: String,
    ): TeamRoleOperationResponse = commandSubmission.submit(
        idempotencyKey,
        TeamRoleCommandType.DELETE,
        teamId,
        actorId,
        roleId = roleId,
    ) {
        teamRoleAccessGuard.lockTeamOrThrow(teamId)
        val actor = teamRoleAccessGuard.requireManageRoles(teamId, actorId)
        val role = teamRoleLookupSupport.findRoleOrThrow(teamId, roleId)
        teamRoleAccessGuard.requireManageablePriority(actor, role)
    }
}
