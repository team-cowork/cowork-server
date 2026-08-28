package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.operation.TeamRoleCommandSubmission
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandType
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleOperationResponse
import com.cowork.team.domain.teamRole.service.AssignTeamRoleService
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import com.cowork.team.domain.teamRole.service.support.TeamRoleLookupSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AssignTeamRoleServiceImpl(
    private val teamRoleAccessGuard: TeamRoleAccessGuard,
    private val teamRoleLookupSupport: TeamRoleLookupSupport,
    private val commandSubmission: TeamRoleCommandSubmission,
) : AssignTeamRoleService {
    @Transactional
    override fun execute(
        actorId: Long,
        teamId: Long,
        targetUserId: Long,
        roleId: Long,
        idempotencyKey: String,
    ): TeamRoleOperationResponse = commandSubmission.submit(
        idempotencyKey,
        TeamRoleCommandType.ASSIGN,
        teamId,
        actorId,
        targetAccountId = targetUserId,
        roleId = roleId,
    ) {
        teamRoleAccessGuard.lockTeamOrThrow(teamId)
        val actor = teamRoleAccessGuard.requireManageRoles(teamId, actorId)
        teamRoleAccessGuard.requireMemberExists(teamId, targetUserId)
        val role = teamRoleLookupSupport.findRoleOrThrow(teamId, roleId)
        teamRoleAccessGuard.requireManageablePriority(actor, role)
    }
}
