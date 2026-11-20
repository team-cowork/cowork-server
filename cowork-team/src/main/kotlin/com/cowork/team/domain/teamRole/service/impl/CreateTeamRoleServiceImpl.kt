package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.operation.TeamRoleCommandRoleInput
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandSubmission
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandType
import com.cowork.team.domain.teamRole.presentation.data.request.CreateTeamRoleRequest
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleOperationResponse
import com.cowork.team.domain.teamRole.service.CreateTeamRoleService
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import com.cowork.team.domain.teamRole.service.support.TeamRolePermissionSchema
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class CreateTeamRoleServiceImpl(
    private val teamRoleAccessGuard: TeamRoleAccessGuard,
    private val teamRoleCommandSubmission: TeamRoleCommandSubmission,
    private val teamRolePermissionSchema: TeamRolePermissionSchema,
) : CreateTeamRoleService {
    @Transactional
    override fun execute(
        actorId: Long,
        teamId: Long,
        idempotencyKey: String,
        request: CreateTeamRoleRequest,
    ): TeamRoleOperationResponse = teamRoleCommandSubmission.submit(
        idempotencyKey = idempotencyKey,
        commandType = TeamRoleCommandType.CREATE,
        teamId = teamId,
        actorId = actorId,
        role = TeamRoleCommandRoleInput.create(request, teamRolePermissionSchema.canonicalize(request.permissions)),
    ) {
        if (request.priority == Int.MAX_VALUE) {
            throw ExpectedException("최상위 우선순위는 소유자 정책에 예약되어 있습니다.", HttpStatus.BAD_REQUEST)
        }
        if (request.name == "@everyone") {
            throw ExpectedException("기본 역할 이름은 사용할 수 없습니다.", HttpStatus.BAD_REQUEST)
        }
        teamRoleAccessGuard.lockTeamOrThrow(teamId)
        val actor = teamRoleAccessGuard.requireManageRoles(teamId, actorId)
        teamRoleAccessGuard.requireGrantablePermissions(
            actor,
            teamRolePermissionSchema.canonicalize(request.permissions),
        )
        if (!actor.administrator &&
            request.priority >= actor.maxPriority
        ) {
            throw ExpectedException(
                "자신의 최상위 역할보다 높거나 같은 역할은 만들 수 없습니다.",
                HttpStatus.FORBIDDEN,
            )
        }
    }
}
