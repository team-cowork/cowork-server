package com.cowork.team.domain.teamRole.operation

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleOperationResponse
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class TeamRoleOperationMapper(private val objectMapper: ObjectMapper) {
    fun toResponse(operation: TeamRoleCommandOperation): TeamRoleOperationResponse = TeamRoleOperationResponse(
        operationId = operation.operationId,
        status = operation.status,
        role = operation.resultRoleJson?.let { objectMapper.readValue(it, TeamRoleResponse::class.java) },
        errorCode = operation.errorCode,
        errorMessage = operation.errorMessage,
    )
}
