package com.cowork.team.domain.teamRole.presentation.data.response

import com.cowork.team.domain.teamRole.operation.TeamRoleOperationStatus
import io.swagger.v3.oas.annotations.media.Schema

data class TeamRoleOperationResponse(
    @field:Schema(description = "서버가 발급한 비동기 작업 UUID")
    val operationId: String,
    @field:Schema(description = "비동기 작업 상태")
    val status: TeamRoleOperationStatus,
    @field:Schema(description = "성공한 역할 작업의 최종 역할 상태")
    val role: TeamRoleResponse? = null,
    @field:Schema(description = "실패 코드")
    val errorCode: String? = null,
    @field:Schema(description = "실패 사유")
    val errorMessage: String? = null,
)
