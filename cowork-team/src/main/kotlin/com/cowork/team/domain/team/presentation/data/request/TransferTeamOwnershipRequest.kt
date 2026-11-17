package com.cowork.team.domain.team.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Positive

data class TransferTeamOwnershipRequest(
    @field:Positive
    @param:Schema(description = "소유권을 받을 팀 멤버의 사용자 ID", minimum = "1", example = "2")
    val targetUserId: Long,
)
