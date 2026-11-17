package com.cowork.team.domain.teamRole.presentation.data.response

import com.cowork.team.domain.teamRole.operation.TeamRoleOperationStatus
import io.swagger.v3.oas.annotations.media.Schema

data class TeamRoleOperationResponse(
    @field:Schema(description = "작업 ID", format = "uuid")
    val operationId: String,
    @field:Schema(description = "작업 상태")
    val status: TeamRoleOperationStatus,
    @field:Schema(description = "작업 성공 시 역할 상태")
    val role: TeamRoleResponse? = null,
    @field:Schema(description = "실패 코드")
    val errorCode: String? = null,
    @field:Schema(description = "실패 사유")
    val errorMessage: String? = null,
)
