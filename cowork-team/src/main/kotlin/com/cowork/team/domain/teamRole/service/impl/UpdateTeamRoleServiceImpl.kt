package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.operation.TeamRoleCommandRoleInput
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandSubmission
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandType
import com.cowork.team.domain.teamRole.presentation.data.request.UpdateTeamRoleRequest
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleOperationResponse
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import com.cowork.team.domain.teamRole.service.UpdateTeamRoleService
import com.cowork.team.domain.teamRole.service.support.TeamRoleLookupSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UpdateTeamRoleServiceImpl(
    private val teamRoleAccessGuard: TeamRoleAccessGuard,
    private val teamRoleLookupSupport: TeamRoleLookupSupport,
    private val commandSubmission: TeamRoleCommandSubmission,
) : UpdateTeamRoleService {
    @Transactional
    override fun execute(
        actorId: Long,
        teamId: Long,
        roleId: Long,
        idempotencyKey: String,
        request: UpdateTeamRoleRequest,
    ): TeamRoleOperationResponse = commandSubmission.submit(
        idempotencyKey = idempotencyKey,
        commandType = TeamRoleCommandType.UPDATE,
        teamId = teamId,
        actorId = actorId,
        roleId = roleId,
        role = TeamRoleCommandRoleInput.update(request),
    ) {
        teamRoleAccessGuard.lockTeamOrThrow(teamId)
        val actor = teamRoleAccessGuard.requireManageRoles(teamId, actorId)
        val role = teamRoleLookupSupport.findRoleOrThrow(teamId, roleId)
        teamRoleAccessGuard.requireManageablePriority(actor, role)
        request.priority?.let {
            teamRoleAccessGuard.requireManageablePriority(actor, role.copy(priority = it))
        }
    }
}
