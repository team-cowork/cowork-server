package com.cowork.team.dto

import io.swagger.v3.oas.annotations.media.Schema

data class InviteMembersRequest(
    @Schema(description = "초대할 사용자 ID 목록", example = "[2, 3, 4]", required = true)
    val userIds: List<Long>,
)