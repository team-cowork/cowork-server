package com.cowork.team.domain.teamInvite.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

data class InviteMembersRequest(
    @param:Schema(description = "초대할 사용자 ID 목록", example = "[2, 3, 4]", required = true)
    val userIds: List<Long>,
)
