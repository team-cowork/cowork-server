package com.cowork.project.domain.githubBoard.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

data class ReorderGithubBoardItemReqDto(
    @param:Schema(title = "보드 뷰 번호", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    val viewNumber: Int,
    @param:Schema(
        title = "앞에 둘 카드 ID",
        description = "같은 컬럼의 카드를 지정합니다. null이면 컬럼의 맨 위로 이동합니다.",
        example = "PVTI_lADOBk9Ru84AqHDWzgUcFQg",
        nullable = true,
    )
    val afterItemId: String?,
)
