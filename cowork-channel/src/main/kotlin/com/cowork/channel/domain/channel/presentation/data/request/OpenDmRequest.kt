package com.cowork.channel.domain.channel.presentation.data.request

import jakarta.validation.constraints.Positive

data class OpenDmRequest(
    @field:Positive(message = "DM 상대 사용자 ID는 양수여야 합니다.")
    val targetUserId: Long,
)
