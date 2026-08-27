package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.operation.TeamRoleCommandOperationRepository
import com.cowork.team.domain.teamRole.operation.TeamRoleOperationMapper
import com.cowork.team.domain.teamRole.operation.TeamRoleOperationProjectionFinalizer
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleOperationResponse
import com.cowork.team.domain.teamRole.service.QueryTeamRoleOperationService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class QueryTeamRoleOperationServiceImpl(
    private val operationRepository: TeamRoleCommandOperationRepository,
    private val operationMapper: TeamRoleOperationMapper,
    private val finalizer: TeamRoleOperationProjectionFinalizer,
) : QueryTeamRoleOperationService {
    @Transactional
    override fun execute(actorId: Long, teamId: Long, operationId: String): TeamRoleOperationResponse {
        val operation = operationRepository.findByIdForUpdate(operationId)
            ?.takeIf { it.teamId == teamId && it.actorId == actorId }
            ?: throw ExpectedException("팀 역할 작업을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        finalizer.tryFinalize(operation)
        return operationMapper.toResponse(operation)
    }
}
