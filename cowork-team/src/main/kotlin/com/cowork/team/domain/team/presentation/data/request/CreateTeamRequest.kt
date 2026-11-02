package com.cowork.team.domain.team.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

data class CreateTeamRequest(
    @param:Schema(
        description = "팀 이름. 공백만으로 구성할 수 없고 최대 100자이며, 앞뒤 공백을 포함해 입력값 그대로 저장합니다.",
        example = "코워크팀",
        required = true,
    )
    val name: String,
    @param:Schema(description = "팀 설명", example = "협업 서비스 개발팀")
    val description: String?,
    @param:Schema(description = "팀 아이콘 URL", example = "https://example.com/icon.png")
    val iconUrl: String?,
)
