package com.cowork.team.domain.teamMember.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

data class ChangeNicknameRequest(
    @param:Schema(description = "팀 닉네임. 비우거나 null이면 팀 닉네임을 지우고 계정 닉네임을 사용합니다.", example = "홍길동 / 프론트엔드")
    val nickname: String?,
)
