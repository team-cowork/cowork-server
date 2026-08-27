package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandRoleInput
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandSubmission
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandType
import com.cowork.team.domain.teamRole.presentation.data.request.CreateTeamRoleRequest
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleOperationResponse
import com.cowork.team.domain.teamRole.service.CreateTeamRoleService
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class CreateTeamRoleServiceImpl(
    private val teamRoleAccessGuard: TeamRoleAccessGuard,
    private val commandSubmission: TeamRoleCommandSubmission,
) : CreateTeamRoleService {
    @Transactional
    override fun execute(
        actorId: Long,
        teamId: Long,
        idempotencyKey: String,
        request: CreateTeamRoleRequest,
    ): TeamRoleOperationResponse = commandSubmission.submit(
        idempotencyKey = idempotencyKey,
        commandType = TeamRoleCommandType.CREATE,
        teamId = teamId,
        actorId = actorId,
        role = TeamRoleCommandRoleInput.create(request),
    ) {
        teamRoleAccessGuard.lockTeamOrThrow(teamId)
        val actor = teamRoleAccessGuard.requireManageRoles(teamId, actorId)
        if (actor.member.role != TeamRole.OWNER &&
            actor.member.role != TeamRole.ADMIN &&
            request.priority >= actor.maxPriority
        ) {
            throw ExpectedException(
                "자신의 최상위 역할보다 높거나 같은 역할은 만들 수 없습니다.",
                HttpStatus.FORBIDDEN,
            )
        }
    }
}
