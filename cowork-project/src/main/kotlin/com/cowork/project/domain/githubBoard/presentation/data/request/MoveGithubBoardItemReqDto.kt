package com.cowork.project.domain.githubBoard.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

data class MoveGithubBoardItemReqDto(
    @param:Schema(title = "보드 뷰 번호", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    val viewNumber: Int,
    @param:Schema(
        title = "대상 컬럼 ID",
        description = "선택 항목 ID 또는 반복 주기 ID를 지정합니다. null은 값이 없는 컬럼을 나타냅니다.",
        example = "98236657",
        nullable = true,
    )
    val columnId: String?,
    @param:Schema(
        title = "앞에 둘 카드 ID",
        description = "대상 컬럼의 카드를 지정합니다. null이면 컬럼의 맨 위로 이동합니다.",
        example = "PVTI_lADOBk9Ru84AqHDWzgUcFQg",
        nullable = true,
    )
    val afterItemId: String?,
)
